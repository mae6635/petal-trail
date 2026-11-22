package fr.petaltrail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Properties;
import java.util.function.IntFunction;
import java.util.concurrent.ThreadLocalRandom;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Petal Trail (mod client uniquement)
 * - Quand tu marches : des petales de cerisier (les vrais du jeu) tombent derriere toi.
 * - Quand tu atterris : un cercle de la couleur des petales s'etend au sol, puis disparait.
 */
public class PetalTrailClient implements ClientModInitializer {

	// ================= REGLAGES (modifie ici puis recompile) =================

	/** Vitesse minimale (blocs par tick) pour laisser une trainee. */
	private static final double MIN_SPEED = 0.04;

	/** Cercle a l'atterrissage : rayon de depart et rayon maximum (en blocs, avant adaptation a la chute). */
	private static final double RING_START = 0.30;

	/** Hauteur de chute minimale (en blocs) pour declencher le cercle. */
	private static final double MIN_FALL = 0.5;

	/** Cercle a l'atterrissage : taille des points de couleur. */
	private static final float RING_SCALE = 0.9f;

	/** Couleurs du cercle (rose petale de cerisier), format 0xRRGGBB. */
	private static final int[] RING_COLORS = { 0xFFB7C5, 0xFF9FBC, 0xFFD3DF };

	/** Lueur outil : chance d'etincelle a chaque tick (0 a 1) - baisse pour plus discret. */
	private static final double TOOL_GLOW_CHANCE = 0.4;

	/** Lueur outil : taille des etincelles (petites = discret). */
	private static final float TOOL_GLOW_SCALE = 0.5f;

	/** Ligne LED : longueur (en ticks de marche, ~0.22 bloc par tick), points par segment (plus = plus continu), hauteur au-dessus du sol. */
	private static final int LED_TRAIL_TICKS = 25;
	private static final int LED_TRAIL_STEPS = 4;
	private static final double LED_TRAIL_HEIGHT = 0.1;

	/** Ligne LED : epaisseur des points de la tete (large) vers la queue (fine). */
	private static final float[] LED_TRAIL_SCALES = { 0.5f, 0.38f, 0.28f };

	/** Aura : etincelles par tick (plus = plus dense), distance au corps (en blocs), taille, couleurs 0xRRGGBB. */
	private static final int AURA_POINTS = 6;
	private static final double AURA_RADIUS = 0.5;
	private static final float AURA_SCALE = 0.4f;
	private static final int[] AURA_COLORS = { 0xFF6FA5, 0xFF9FBC };

	/** Explosion : fenetre de detection apres ton dernier coup (en ticks, 20 = 1 seconde), nombre de petales et d'etincelles. */
	private static final int KILL_WINDOW_TICKS = 100;
	private static final int KILL_SPARKS = 60;

	private static final int BOLT_DENSITY = 14;
	private static final int BOLT_TICKS = 10;

	// ==========================================================================

	// ============ REGLAGES MODIFIABLES EN JEU (commande /interfacemod) ============

	static double trailChance = 0.6;
	static double trailExtra = 0.3;
	static int ringTicks = 12;
	static double ringRadius = 1.6;
	static int killPetals = 320;
	static double boltHeight = 9.0;
	/** true = les animations s'affichent sur tous les joueurs que tu vois ; false = seulement toi. */
	static boolean allPlayers = true;
	static boolean ledOn = true;
	static boolean auraOn = false;
	static boolean toolOn = false;
	static boolean killOn = true;
	static boolean lightningOn = true;

	/** Elements dont on peut changer la couleur. */
	static final int EL_PETAL = 0, EL_TRAIL = 1, EL_RING = 2, EL_AURA = 3, EL_TOOL = 4, EL_BOLT = 5;
	static final String[] EL_LABELS = {
		"Petales", "Ligne LED", "Cercle atterrissage", "Aura", "Outil", "Eclair / explosion"
	};
	/** Choix de couleur par element : 0 = defaut, 1..20 = couleurs nommees, derniere = arc-en-ciel. */
	static final int[] colorChoice = new int[EL_LABELS.length];

	static final int[] RAINBOW = {
		0xFF3B3B, 0xFF8C2E, 0xFFEE58, 0xB6FF3C, 0x3DDC6B, 0x30E3D0,
		0x3CD8FF, 0x3D7BFF, 0x5B4BFF, 0x9B5BFF, 0xFF3DDB, 0xFF4FA3
	};

	static final String[] COLOR_NAMES = {
		"rose", "rouge", "corail", "orange", "or", "jaune", "lime", "vert", "menthe", "turquoise",
		"cyan", "bleu", "indigo", "violet", "lavande", "magenta", "fuchsia", "marron", "blanc", "gris"
	};
	static final int[] COLOR_VALUES = {
		0xFF9FBC, 0xFF3B3B, 0xFF7F6B, 0xFF8C2E, 0xFFC933, 0xFFEE58, 0xB6FF3C, 0x3DDC6B, 0x7FFFC8, 0x30E3D0,
		0x3CD8FF, 0x3D7BFF, 0x5B4BFF, 0x9B5BFF, 0xC3A5FF, 0xFF3DDB, 0xFF4FA3, 0xA9703A, 0xF2F2F2, 0x9AA0A6
	};
	/** Options de l'interface : defaut + 20 couleurs + arc-en-ciel. */
	static final int OPTION_COUNT = COLOR_NAMES.length + 2;
	static final int RAINBOW_IDX = OPTION_COUNT - 1;

	static String optionName(int i) {
		return i == 0 ? "defaut" : (i == RAINBOW_IDX ? "arc-en-ciel" : COLOR_NAMES[i - 1]);
	}

	static int optionColor(int i) {
		return i == 0 ? 0xFFFFFF : (i == RAINBOW_IDX ? 0xFF8CE0 : COLOR_VALUES[i - 1]);
	}

	// Palettes calculees a partir des choix (voir rebuild()).
	private static int[] ringPalette = RING_COLORS;
	private static int[] toolPalette = RING_COLORS;
	private static int[] auraPalette = AURA_COLORS;
	private static int[] trailPalette = { 0xFFB7C5 };
	private static int[] boltPalette = { 0xFFB7C5 };
	/** Teinte des petales (null = rose d'origine) ; une teinte au hasard par petale. */
	private static float[][] petalTints = null;

	private static DustParticleOptions[] RING_PARTICLES;
	private static DustParticleOptions[] GLOW_PARTICLES;
	private static DustParticleOptions[] AURA_PARTICLES;
	private static DustParticleOptions[][] TRAIL_PARTICLES;
	private static DustParticleOptions[][] BOLT_PARTICLES;

	static {
		loadSavedSettings();
		rebuild();
	}

	private static DustParticleOptions[] dust(int[] pal, float scale) {
		DustParticleOptions[] out = new DustParticleOptions[pal.length];
		for (int i = 0; i < pal.length; i++) {
			out[i] = new DustParticleOptions(pal[i], scale);
		}
		return out;
	}

	private static int[] palette(int el, int[] def, IntFunction<int[]> single) {
		int c = colorChoice[el];
		if (c <= 0) {
			return def;
		}
		if (c == RAINBOW_IDX) {
			return RAINBOW;
		}
		return single.apply(COLOR_VALUES[c - 1]);
	}

	private static float[] tint(int c) {
		// Les petales du jeu sont roses : on corrige la teinte pour se rapprocher de la couleur voulue.
		return new float[] {
			Math.min(1f, ((c >> 16) & 255) / 255f),
			Math.min(1f, ((c >> 8) & 255) / 255f / 0.72f),
			Math.min(1f, (c & 255) / 255f / 0.77f)
		};
	}

	/** Recalcule toutes les palettes et particules a partir des choix de couleur. */
	static void rebuild() {
		int c = colorChoice[EL_PETAL];
		if (c <= 0) {
			petalTints = null;
		} else if (c == RAINBOW_IDX) {
			petalTints = new float[RAINBOW.length][];
			for (int i = 0; i < RAINBOW.length; i++) {
				petalTints[i] = tint(RAINBOW[i]);
			}
		} else {
			petalTints = new float[][] { tint(COLOR_VALUES[c - 1]) };
		}
		trailPalette = palette(EL_TRAIL, new int[] { 0xFFB7C5 }, b -> new int[] { mix(b, 0xFFFFFF, 0.25) });
		ringPalette = palette(EL_RING, RING_COLORS, b -> new int[] { mix(b, 0xFFFFFF, 0.3), b, mix(b, 0xFFFFFF, 0.55) });
		toolPalette = palette(EL_TOOL, RING_COLORS, b -> new int[] { mix(b, 0xFFFFFF, 0.3), b, mix(b, 0xFFFFFF, 0.55) });
		auraPalette = palette(EL_AURA, AURA_COLORS, b -> new int[] { mix(b, 0x000000, 0.12), b });
		boltPalette = palette(EL_BOLT, new int[] { 0xFFB7C5 }, b -> new int[] { b });

		RING_PARTICLES = dust(ringPalette, RING_SCALE);
		GLOW_PARTICLES = dust(toolPalette, TOOL_GLOW_SCALE);
		AURA_PARTICLES = dust(auraPalette, AURA_SCALE);
		TRAIL_PARTICLES = new DustParticleOptions[LED_TRAIL_SCALES.length][];
		for (int t = 0; t < LED_TRAIL_SCALES.length; t++) {
			TRAIL_PARTICLES[t] = dust(trailPalette, LED_TRAIL_SCALES[t]);
		}
		float[] scales = { 0.3f, 0.65f, 0.22f };
		BOLT_PARTICLES = new DustParticleOptions[3][boltPalette.length];
		for (int i = 0; i < boltPalette.length; i++) {
			int core = mix(boltPalette[i], 0xFFFFFF, 0.65);
			BOLT_PARTICLES[0][i] = new DustParticleOptions(core, scales[0]);
			BOLT_PARTICLES[1][i] = new DustParticleOptions(boltPalette[i], scales[1]);
			BOLT_PARTICLES[2][i] = new DustParticleOptions(core, scales[2]);
		}
	}

	static int mix(int c, int target, double a) {
		int r = (int) Math.round(((c >> 16) & 255) * (1 - a) + ((target >> 16) & 255) * a);
		int g = (int) Math.round(((c >> 8) & 255) * (1 - a) + ((target >> 8) & 255) * a);
		int b = (int) Math.round((c & 255) * (1 - a) + (target & 255) * a);
		return (r << 16) | (g << 8) | b;
	}

	/** Mis a true par /interfacemod : l'ecran s'ouvre au tick suivant (le chat doit d'abord se fermer). */
	private static boolean openGui = false;

	private int animTick = 0;

	/** Derniere cible que tu as frappee (pour detecter ton kill) et sa derniere position connue. */
	private Entity lastTarget = null;
	private int lastTargetTick = 0;
	private double lastX, lastY, lastZ, lastH;

	private static final class State {
		double x, y, z;
		boolean onGround;
		/** Point le plus haut atteint pendant le vol en cours. */
		double peakY;
		/** Chemin recent du joueur (le plus recent en premier) pour dessiner la ligne LED. */
		final ArrayDeque<double[]> path = new ArrayDeque<>();

		void set(double x, double y, double z, boolean onGround) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.onGround = onGround;
			this.peakY = onGround ? y : Math.max(this.peakY, y);
		}

		void reset(double x, double y, double z, boolean onGround) {
			set(x, y, z, onGround);
			this.peakY = y;
			this.path.clear();
		}
	}

	private static final class Bolt {
		/** Ligne principale (index 0) et branches : suites de points (x, y, z). */
		final List<double[][]> lines = new ArrayList<>();
		int age = 0;
	}

	private static final class Ring {
		final double x, y, z;
		final double strength;
		int age = 0;

		Ring(double x, double y, double z, double strength) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.strength = strength;
		}
	}

	private final Map<UUID, State> states = new HashMap<>();
	private final List<Ring> rings = new ArrayList<>();
	private final List<Bolt> bolts = new ArrayList<>();

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClientSide() && entity instanceof LivingEntity && player == Minecraft.getInstance().player) {
				lastTarget = entity;
				lastTargetTick = animTick;
			}
			return InteractionResult.PASS;
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
			ClientCommandManager.literal("interfacemod").executes(ctx -> {
				openGui = true;
				return 1;
			})
		));
	}

	// ================= SAUVEGARDE DES REGLAGES =================

	private static Path settingsFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("petal_trail.properties");
	}

	static void saveSettings() {
		Properties p = new Properties();
		for (int i = 0; i < colorChoice.length; i++) {
			p.setProperty("color" + i, Integer.toString(colorChoice[i]));
		}
		p.setProperty("trailChance", Double.toString(trailChance));
		p.setProperty("trailExtra", Double.toString(trailExtra));
		p.setProperty("ringTicks", Integer.toString(ringTicks));
		p.setProperty("ringRadius", Double.toString(ringRadius));
		p.setProperty("killPetals", Integer.toString(killPetals));
		p.setProperty("boltHeight", Double.toString(boltHeight));
		p.setProperty("allPlayers", Boolean.toString(allPlayers));
		p.setProperty("ledOn", Boolean.toString(ledOn));
		p.setProperty("auraOn", Boolean.toString(auraOn));
		p.setProperty("toolOn", Boolean.toString(toolOn));
		p.setProperty("killOn", Boolean.toString(killOn));
		p.setProperty("lightningOn", Boolean.toString(lightningOn));
		try (var out = Files.newOutputStream(settingsFile())) {
			p.store(out, "Petal Trail");
		} catch (IOException ignored) {
			// pas grave : les reglages ne seront juste pas retenus
		}
	}

	private static void loadSavedSettings() {
		try {
			Path f = settingsFile();
			if (!Files.exists(f)) {
				return;
			}
			Properties p = new Properties();
			try (var in = Files.newInputStream(f)) {
				p.load(in);
			}
			for (int i = 0; i < colorChoice.length; i++) {
				int v = Integer.parseInt(p.getProperty("color" + i, "0"));
				colorChoice[i] = (v >= 0 && v < OPTION_COUNT) ? v : 0;
			}
			trailChance = Double.parseDouble(p.getProperty("trailChance", "0.6"));
			trailExtra = Double.parseDouble(p.getProperty("trailExtra", "0.3"));
			ringTicks = Integer.parseInt(p.getProperty("ringTicks", "12"));
			ringRadius = Double.parseDouble(p.getProperty("ringRadius", "1.6"));
			killPetals = Integer.parseInt(p.getProperty("killPetals", "320"));
			boltHeight = Double.parseDouble(p.getProperty("boltHeight", "9.0"));
			allPlayers = Boolean.parseBoolean(p.getProperty("allPlayers", "true"));
			ledOn = Boolean.parseBoolean(p.getProperty("ledOn", "true"));
			auraOn = Boolean.parseBoolean(p.getProperty("auraOn", "false"));
			toolOn = Boolean.parseBoolean(p.getProperty("toolOn", "false"));
			killOn = Boolean.parseBoolean(p.getProperty("killOn", "true"));
			lightningOn = Boolean.parseBoolean(p.getProperty("lightningOn", "true"));
		} catch (IOException | RuntimeException ignored) {
			// fichier illisible : on garde les valeurs par defaut
		}
	}

	/** Remet tout aux valeurs d'origine. */
	static void resetSettings() {
		java.util.Arrays.fill(colorChoice, 0);
		trailChance = 0.6;
		trailExtra = 0.3;
		ringTicks = 12;
		ringRadius = 1.6;
		killPetals = 320;
		boltHeight = 9.0;
		allPlayers = true;
		ledOn = true;
		auraOn = false;
		toolOn = false;
		killOn = true;
		lightningOn = true;
		rebuild();
	}

	private void onTick(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null) {
			openGui = false;
			states.clear();
			rings.clear();
			bolts.clear();
			lastTarget = null;
			return;
		}
		if (client.isPaused()) {
			return;
		}

		if (openGui) {
			openGui = false;
			client.setScreen(new PetalTrailScreen());
		}

		animTick++;
		Set<UUID> seen = new HashSet<>();
		for (Player player : level.players()) {
			if (!allPlayers && player != client.player) {
				continue;
			}
			if (player.isSpectator()) {
				continue;
			}
			seen.add(player.getUUID());
			handlePlayer(level, player);
			if (toolOn) {
				emitToolGlow(level, player);
			}
			if (auraOn && !player.isInvisible()) {
				emitAura(level, player);
			}
		}
		states.keySet().retainAll(seen);

		tickRings(level);
		tickBolts(level);

		if (killOn) {
			checkKill(level);
		}
	}

	private void handlePlayer(ClientLevel level, Player player) {
		double x = player.getX();
		double y = player.getY();
		double z = player.getZ();
		boolean ground = player.onGround();

		State s = states.get(player.getUUID());
		if (s == null) {
			s = new State();
			s.reset(x, y, z, ground);
			states.put(player.getUUID(), s);
			return;
		}

		double dx = x - s.x;
		double dy = y - s.y;
		double dz = z - s.z;
		double speed = Math.hypot(dx, dz);

		// Teleportation / changement de monde : on repart de zero.
		if (speed > 3.0 || Math.abs(dy) > 3.0) {
			s.reset(x, y, z, ground);
			return;
		}

		// Atterrissage : on etait en l'air au tick precedent, on touche le sol maintenant.
		double fall = s.peakY - y;
		if (!s.onGround && ground && fall > MIN_FALL) {
			double strength = Math.max(0.6, Math.min(1.5, fall / 2.5));
			rings.add(new Ring(x, y, z, strength));
		}

		// Marche : petales derriere le joueur.
		if (speed > MIN_SPEED) {
			emitTrail(level, x, y, z, dx / speed, dz / speed);
		}

		if (ledOn) {
			updateLedTrail(level, s, x, y, z, speed > MIN_SPEED);
		}

		s.set(x, y, z, ground);
	}

	/**
	 * Ligne LED continue : on garde le chemin des derniers ticks et on le redessine a chaque tick avec des
	 * points tres serres (large pres de toi, fin vers la queue). A l'arret, la ligne rentre doucement vers toi.
	 */
	private void updateLedTrail(ClientLevel level, State s, double x, double y, double z, boolean moving) {
		if (moving) {
			s.path.addFirst(new double[] { x, y + LED_TRAIL_HEIGHT, z });
			while (s.path.size() > LED_TRAIL_TICKS) {
				s.path.removeLast();
			}
		} else if (!s.path.isEmpty()) {
			s.path.removeLast();
		}
		int n = s.path.size();
		if (n < 2) {
			return;
		}

		ThreadLocalRandom r = ThreadLocalRandom.current();
		double[] prev = null;
		int i = 0;
		for (double[] p : s.path) {
			if (prev != null) {
				double t = (double) i / (n - 1);
				int tier = t < 0.35 ? 0 : (t < 0.7 ? 1 : 2);
				int ci = trailPalette.length > 1 ? (i + animTick / 2) % trailPalette.length : 0;
				for (int k = 0; k < LED_TRAIL_STEPS; k++) {
					if (r.nextDouble() < t * 0.5) {
						continue;
					}
					double f = (double) k / LED_TRAIL_STEPS;
					level.addParticle(
						TRAIL_PARTICLES[tier][ci],
						prev[0] + (p[0] - prev[0]) * f,
						prev[1] + (p[1] - prev[1]) * f,
						prev[2] + (p[2] - prev[2]) * f,
						0.0, 0.0, 0.0
					);
				}
			}
			prev = p;
			i++;
		}
	}

	/** Si la cible que tu viens de frapper est morte, on declenche l'explosion la ou elle est tombee. */
	private void checkKill(ClientLevel level) {
		Entity t = lastTarget;
		if (t == null) {
			return;
		}
		if (animTick - lastTargetTick > KILL_WINDOW_TICKS) {
			lastTarget = null;
			return;
		}
		if (t instanceof LivingEntity le && le.isDeadOrDying()) {
			lastTarget = null;
			killBurst(level, lastX, lastY, lastZ, lastH);
			return;
		}
		lastX = t.getX();
		lastY = t.getY();
		lastZ = t.getZ();
		lastH = t.getBbHeight();
	}

	/** Explosion : petales + etincelles projetes dans toutes les directions, et grand cercle au sol. */
	private void killBurst(ClientLevel level, double x, double y, double z, double h) {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		double cy = y + h * 0.5;
		rings.add(new Ring(x, y, z, 1.5));
		if (lightningOn) {
			bolts.add(makeBolt(r, x, y, z));
		}

		for (int i = 0; i < killPetals; i++) {
			double[] v = burstVelocityUp(r, 0.08, 0.2);
			Particle p = Minecraft.getInstance().particleEngine.createParticle(ParticleTypes.CHERRY_LEAVES, x, cy, z, 0.0, 0.0, 0.0);
			if (p != null) {
				p.setParticleSpeed(v[0], v[1] + 0.12, v[2]);
				float[][] tints = petalTints;
				if (tints != null) {
					float[] t = tints[r.nextInt(tints.length)];
					p.setColor(t[0], t[1], t[2]);
				}
			}
		}

		for (int i = 0; i < KILL_SPARKS; i++) {
			double[] v = burstVelocity(r, 0.06, 0.14);
			Particle p = Minecraft.getInstance().particleEngine.createParticle(
				RING_PARTICLES[r.nextInt(RING_PARTICLES.length)], x, cy, z, 0.0, 0.0, 0.0);
			if (p != null) {
				p.setParticleSpeed(v[0], v[1], v[2]);
			}
		}
	}

	/** Ligne brisee par deplacement du point milieu : donne un vrai zigzag d'eclair (plus iters = plus de details). */
	private static double[][] jag(ThreadLocalRandom r, double[] a, double[] b, int iters, double amp) {
		List<double[]> pts = new ArrayList<>();
		pts.add(a);
		pts.add(b);
		for (int it = 0; it < iters; it++) {
			List<double[]> next = new ArrayList<>();
			next.add(pts.get(0));
			for (int i = 1; i < pts.size(); i++) {
				double[] p = pts.get(i - 1);
				double[] q = pts.get(i);
				next.add(new double[] {
					(p[0] + q[0]) / 2 + (r.nextDouble() - 0.5) * amp,
					(p[1] + q[1]) / 2,
					(p[2] + q[2]) / 2 + (r.nextDouble() - 0.5) * amp
				});
				next.add(q);
			}
			pts = next;
			amp *= 0.55;
		}
		return pts.toArray(new double[0][]);
	}

	/** Eclair du ciel jusqu'au point de mort : tige principale tres anguleuse + 3 branches. */
	private static Bolt makeBolt(ThreadLocalRandom r, double x, double y, double z) {
		Bolt bolt = new Bolt();
		double[] top = { x + (r.nextDouble() - 0.5) * 3.0, y + boltHeight, z + (r.nextDouble() - 0.5) * 3.0 };
		double[][] main = jag(r, top, new double[] { x, y, z }, 5, 2.2);
		bolt.lines.add(main);

		for (int br = 0; br < 3; br++) {
			double[] s = main[6 + r.nextInt(main.length - 13)];
			double ang = r.nextDouble() * Math.PI * 2.0;
			double[] e = {
				s[0] + Math.cos(ang) * 1.8,
				Math.max(y, s[1] - 2.5 - r.nextDouble() * 1.5),
				s[2] + Math.sin(ang) * 1.8
			};
			bolt.lines.add(jag(r, s, e, 3, 0.9));
		}
		return bolt;
	}

	/**
	 * Dessine l'eclair : flash tres net les 2 premiers ticks (coeur clair + lueur), puis il s'eteint en
	 * scintillant. Un flash d'etincelles claires part de l'impact au sol.
	 */
	private void tickBolts(ClientLevel level) {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		Iterator<Bolt> it = bolts.iterator();

		while (it.hasNext()) {
			Bolt b = it.next();
			boolean flicker = b.age == 3 || b.age == 6;
			double fade = 1.0 - (double) b.age / BOLT_TICKS;

			if (b.age == 0) {
				impactFlash(level, b.lines.get(0), r);
			}

			if (!flicker) {
				for (int li = 0; li < b.lines.size(); li++) {
					double[][] line = b.lines.get(li);
					boolean isMain = li == 0;
					for (int i = 1; i < line.length; i++) {
						double[] a = line[i - 1];
						double[] c = line[i];
						double len = Math.sqrt(
							(c[0] - a[0]) * (c[0] - a[0]) + (c[1] - a[1]) * (c[1] - a[1]) + (c[2] - a[2]) * (c[2] - a[2]));
						int steps = Math.max(1, (int) (len * BOLT_DENSITY));
						int ci = boltPalette.length > 1 ? ((i / 2) + animTick / 2) % boltPalette.length : 0;
						for (int k = 0; k < steps; k++) {
							if (b.age >= 2 && r.nextDouble() > fade) {
								continue;
							}
							double f = (k + r.nextDouble()) / steps;
							double px = a[0] + (c[0] - a[0]) * f;
							double py = a[1] + (c[1] - a[1]) * f;
							double pz = a[2] + (c[2] - a[2]) * f;
							level.addParticle(BOLT_PARTICLES[isMain ? 0 : 2][ci], px, py, pz, 0.0, 0.0, 0.0);
							if (isMain && r.nextInt(3) == 0) {
								level.addParticle(BOLT_PARTICLES[1][ci],
									px + (r.nextDouble() - 0.5) * 0.12, py + (r.nextDouble() - 0.5) * 0.12,
									pz + (r.nextDouble() - 0.5) * 0.12, 0.0, 0.0, 0.0);
							}
						}
					}
				}
			}

			if (b.age < 6) {
				double[][] main = b.lines.get(0);
				for (int i = 0; i < 14; i++) {
					double[] q = main[r.nextInt(main.length)];
					spawnPetal(level, q[0], q[1], q[2]);
				}
			}

			b.age++;
			if (b.age >= BOLT_TICKS) {
				it.remove();
			}
		}
	}

	/** Flash au point d'impact : etincelles claires projetees a plat et vers le haut. */
	private void impactFlash(ClientLevel level, double[][] main, ThreadLocalRandom r) {
		double[] e = main[main.length - 1];
		for (int i = 0; i < 40; i++) {
			double a = r.nextDouble() * Math.PI * 2.0;
			boolean up = i >= 28;
			double sp = up ? 0.0 : 0.08 + r.nextDouble() * 0.12;
			Particle p = Minecraft.getInstance().particleEngine.createParticle(
				BOLT_PARTICLES[0][r.nextInt(BOLT_PARTICLES[0].length)], e[0], e[1] + 0.1, e[2], 0.0, 0.0, 0.0);
			if (p != null) {
				p.setParticleSpeed(Math.cos(a) * sp, up ? 0.1 + r.nextDouble() * 0.12 : 0.02, Math.sin(a) * sp);
			}
		}
	}

	/** Direction au hasard surtout vers le haut (les petales montent haut avant de retomber). */
	private static double[] burstVelocityUp(ThreadLocalRandom r, double min, double max) {
		double a = r.nextDouble() * Math.PI * 2.0;
		double up = 0.25 + r.nextDouble() * 0.75;
		double horizontal = Math.sqrt(1.0 - up * up);
		double speed = min + r.nextDouble() * (max - min);
		return new double[] { Math.cos(a) * horizontal * speed, up * speed, Math.sin(a) * horizontal * speed };
	}

	/** Direction au hasard (un peu vers le haut) avec une vitesse entre min et max. */
	private static double[] burstVelocity(ThreadLocalRandom r, double min, double max) {
		double a = r.nextDouble() * Math.PI * 2.0;
		double up = r.nextDouble() * 0.8 - 0.15;
		double horizontal = Math.sqrt(1.0 - up * up);
		double speed = min + r.nextDouble() * (max - min);
		return new double[] { Math.cos(a) * horizontal * speed, up * speed, Math.sin(a) * horizontal * speed };
	}

	/** Petale de cerisier du jeu, teinte avec la couleur du theme (/interfacemod) si ce n'est pas le rose. */
	private void spawnPetal(ClientLevel level, double x, double y, double z) {
		float[][] tints = petalTints;
		if (tints == null) {
			level.addParticle(ParticleTypes.CHERRY_LEAVES, x, y, z, 0.0, 0.0, 0.0);
			return;
		}
		Particle p = Minecraft.getInstance().particleEngine.createParticle(ParticleTypes.CHERRY_LEAVES, x, y, z, 0.0, 0.0, 0.0);
		if (p != null) {
			float[] t = tints[ThreadLocalRandom.current().nextInt(tints.length)];
			p.setColor(t[0], t[1], t[2]);
		}
	}

	private void emitTrail(ClientLevel level, double x, double y, double z, double ux, double uz) {
		ThreadLocalRandom r = ThreadLocalRandom.current();

		int count = r.nextDouble() < trailChance ? 1 : 0;
		if (r.nextDouble() < trailExtra) {
			count++;
		}

		for (int i = 0; i < count; i++) {
			double height = 0.05 + Math.pow(r.nextDouble(), 1.4) * 1.7;
			double back = 0.25 + r.nextDouble() * 0.2;
			double side = (r.nextDouble() - 0.5) * 0.6;

			double px = x - ux * back + (-uz) * side;
			double pz = z - uz * back + ux * side;

			spawnPetal(level, px, y + height, pz);
		}
	}

	/** Etincelles roses placees autour du corps (hauteur et angle au hasard) qui montent doucement. */
	private void emitAura(ClientLevel level, Player player) {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		double height = player.getBbHeight();
		for (int i = 0; i < AURA_POINTS; i++) {
			double angle = r.nextDouble() * Math.PI * 2.0;
			double dist = AURA_RADIUS * (0.85 + r.nextDouble() * 0.3);
			level.addParticle(
				AURA_PARTICLES[r.nextInt(AURA_PARTICLES.length)],
				player.getX() + Math.cos(angle) * dist,
				player.getY() + 0.05 + r.nextDouble() * height,
				player.getZ() + Math.sin(angle) * dist,
				0.0, 0.01, 0.0
			);
		}
	}

	private static boolean isGlowTool(ItemStack stack) {
		return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES)
			|| stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES);
	}

	/** Petites etincelles rose LED le long de l'outil tenu, avec parfois un vrai petale. */
	private void emitToolGlow(ClientLevel level, Player player) {
		if (!isGlowTool(player.getMainHandItem())) {
			return;
		}
		ThreadLocalRandom r = ThreadLocalRandom.current();
		if (r.nextDouble() >= TOOL_GLOW_CHANCE) {
			return;
		}

		double yaw = Math.toRadians(player.getYRot());
		double side = player.getMainArm() == HumanoidArm.RIGHT ? 1.0 : -1.0;
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // devant le joueur
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw);  // a sa droite

		double t = r.nextDouble();                        // position le long de l'outil
		double reach = 0.45 + t * 0.35;
		double px = player.getX() + fx * reach + rx * 0.35 * side + (r.nextDouble() - 0.5) * 0.12;
		double py = player.getY() + 0.95 + t * 0.35 + (r.nextDouble() - 0.5) * 0.12;
		double pz = player.getZ() + fz * reach + rz * 0.35 * side + (r.nextDouble() - 0.5) * 0.12;

		level.addParticle(GLOW_PARTICLES[r.nextInt(GLOW_PARTICLES.length)], px, py, pz, 0.0, 0.01, 0.0);
		if (r.nextDouble() < 0.12) {
			spawnPetal(level, px, py, pz);
		}
	}

	private void tickRings(ClientLevel level) {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		Iterator<Ring> it = rings.iterator();

		while (it.hasNext()) {
			Ring ring = it.next();
			double t = (double) ring.age / ringTicks;

			if (ring.age == 0) {
				burst(level, ring, r);
			}

			// Le cercle s'ouvre vite puis ralentit, et s'eclaircit en grandissant.
			double ease = 1.0 - (1.0 - t) * (1.0 - t);
			double radius = RING_START + (ringRadius * ring.strength - RING_START) * ease;
			int points = Math.max(10, (int) (radius * 22.0));
			double keep = 0.75 * (1.0 - t * 0.8);

			for (int i = 0; i < points; i++) {
				if (r.nextDouble() > keep) {
					continue;
				}
				double angle = r.nextDouble() * Math.PI * 2.0;
				double rr = radius + (r.nextDouble() - 0.5) * 0.25;
				DustParticleOptions dust = RING_PARTICLES[r.nextInt(RING_PARTICLES.length)];
				level.addParticle(
					dust,
					ring.x + Math.cos(angle) * rr,
					ring.y + 0.04 + r.nextDouble() * 0.08,
					ring.z + Math.sin(angle) * rr,
					0.0, 0.01, 0.0
				);
			}

			ring.age++;
			if (ring.age >= ringTicks) {
				it.remove();
			}
		}
	}

	/** Petit nuage de vrais petales souleves par l'impact. */
	private void burst(ClientLevel level, Ring ring, ThreadLocalRandom r) {
		int count = (int) Math.round(6 + 8 * ring.strength);
		for (int i = 0; i < count; i++) {
			double angle = r.nextDouble() * Math.PI * 2.0;
			double dist = 0.1 + r.nextDouble() * 0.5 * ring.strength;
			spawnPetal(level, ring.x + Math.cos(angle) * dist,
				ring.y + 0.08 + r.nextDouble() * 0.25,
				ring.z + Math.sin(angle) * dist);
		}
	}
}

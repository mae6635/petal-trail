package fr.petaltrail;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

/**
 * Interface en jeu ouverte par /interfacemod : 3 onglets (couleurs, reglages, effets).
 * Tout est enregistre dans config/petal_trail.properties a la fermeture.
 */
public class PetalTrailScreen extends Screen {

	private int tab = 0;

	public PetalTrailScreen() {
		super(Component.literal("Petal Trail"));
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int y = 20;

		String[] tabs = { "Couleurs", "Reglages", "Effets" };
		for (int i = 0; i < tabs.length; i++) {
			final int t = i;
			addRenderableWidget(Button.builder(Component.literal((tab == i ? "> " : "") + tabs[i]), b -> {
				tab = t;
				this.rebuildWidgets();
			}).bounds(cx - 155 + i * 105, y, 100, 20).build());
		}
		y += 32;

		if (tab == 0) {
			colorsTab(cx, y);
		} else if (tab == 1) {
			settingsTab(cx, y);
		} else {
			effectsTab(cx, y);
		}

		addRenderableWidget(Button.builder(Component.literal("Reinitialiser"), b -> {
			PetalTrailClient.resetSettings();
			this.rebuildWidgets();
		}).bounds(cx - 155, this.height - 28, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Fermer"), b -> this.onClose())
			.bounds(cx + 55, this.height - 28, 100, 20).build());
	}

	// ---------------- onglet couleurs ----------------

	private void colorsTab(int cx, int y) {
		for (int el = 0; el < PetalTrailClient.EL_LABELS.length; el++) {
			colorRow(cx, y, el);
			y += 24;
		}
		// Ligne "Tout" : applique la meme couleur a tous les elements.
		addRenderableWidget(Button.builder(Component.literal("Tout : appliquer une couleur"), b -> {
			int next = (PetalTrailClient.colorChoice[0] + 1) % PetalTrailClient.OPTION_COUNT;
			java.util.Arrays.fill(PetalTrailClient.colorChoice, next);
			PetalTrailClient.rebuild();
			this.rebuildWidgets();
		}).bounds(cx - 100, y + 6, 200, 20).build());
	}

	private void colorRow(int cx, int y, int el) {
		addRenderableWidget(Button.builder(Component.literal("<"), b -> {
			shift(el, -1);
		}).bounds(cx - 120, y, 20, 20).build());
		addRenderableWidget(Button.builder(colorLabel(el), b -> {
			shift(el, 1);
		}).bounds(cx - 98, y, 196, 20).build());
		addRenderableWidget(Button.builder(Component.literal(">"), b -> {
			shift(el, 1);
		}).bounds(cx + 100, y, 20, 20).build());
	}

	private void shift(int el, int d) {
		int n = PetalTrailClient.OPTION_COUNT;
		PetalTrailClient.colorChoice[el] = (PetalTrailClient.colorChoice[el] + d + n) % n;
		PetalTrailClient.rebuild();
		this.rebuildWidgets();
	}

	private Component colorLabel(int el) {
		int c = PetalTrailClient.colorChoice[el];
		int rgb = PetalTrailClient.optionColor(c);
		return Component.literal(PetalTrailClient.EL_LABELS[el] + " : ")
			.append(Component.literal(PetalTrailClient.optionName(c)).withStyle(s -> s.withColor(TextColor.fromRgb(rgb))));
	}

	// ---------------- onglet reglages ----------------

	private void settingsTab(int cx, int y) {
		int x = cx - 120;
		slider(x, y, "Petales a la marche", 0, 1, 0.05, () -> PetalTrailClient.trailChance, v -> PetalTrailClient.trailChance = v);
		y += 24;
		slider(x, y, "Petales bonus", 0, 1, 0.05, () -> PetalTrailClient.trailExtra, v -> PetalTrailClient.trailExtra = v);
		y += 24;
		slider(x, y, "Duree du cercle (ticks)", 4, 20, 1, () -> PetalTrailClient.ringTicks, v -> PetalTrailClient.ringTicks = (int) Math.round(v));
		y += 24;
		slider(x, y, "Rayon du cercle", 0.8, 3, 0.1, () -> PetalTrailClient.ringRadius, v -> PetalTrailClient.ringRadius = v);
		y += 24;
		slider(x, y, "Petales a la mort", 0, 600, 10, () -> PetalTrailClient.killPetals, v -> PetalTrailClient.killPetals = (int) Math.round(v));
		y += 24;
		slider(x, y, "Hauteur de l'eclair", 3, 15, 1, () -> PetalTrailClient.boltHeight, v -> PetalTrailClient.boltHeight = v);
	}

	private void slider(int x, int y, String label, double min, double max, double step, DoubleSupplier get, DoubleConsumer set) {
		addRenderableWidget(new ValueSlider(x, y, 240, 20, label, min, max, step, get.getAsDouble(), set));
	}

	private static final class ValueSlider extends AbstractSliderButton {
		private final String label;
		private final double min, max, step;
		private final DoubleConsumer set;

		ValueSlider(int x, int y, int w, int h, String label, double min, double max, double step, double value, DoubleConsumer set) {
			super(x, y, w, h, Component.empty(), (value - min) / (max - min));
			this.label = label;
			this.min = min;
			this.max = max;
			this.step = step;
			this.set = set;
			updateMessage();
		}

		private double current() {
			double v = min + this.value * (max - min);
			return Math.max(min, Math.min(max, Math.round(v / step) * step));
		}

		@Override
		protected void updateMessage() {
			double v = current();
			String txt = step >= 1 ? Integer.toString((int) Math.round(v)) : String.format("%.2f", v);
			setMessage(Component.literal(label + " : " + txt));
		}

		@Override
		protected void applyValue() {
			set.accept(current());
		}
	}

	// ---------------- onglet effets ----------------

	private void effectsTab(int cx, int y) {
		int x = cx - 120;
		toggle(x, y, "Visible sur les autres joueurs", () -> PetalTrailClient.allPlayers, () -> PetalTrailClient.allPlayers = !PetalTrailClient.allPlayers);
		y += 24;
		toggle(x, y, "Ligne LED derriere toi", () -> PetalTrailClient.ledOn, () -> PetalTrailClient.ledOn = !PetalTrailClient.ledOn);
		y += 24;
		toggle(x, y, "Aura autour du corps", () -> PetalTrailClient.auraOn, () -> PetalTrailClient.auraOn = !PetalTrailClient.auraOn);
		y += 24;
		toggle(x, y, "Etincelles sur l'outil", () -> PetalTrailClient.toolOn, () -> PetalTrailClient.toolOn = !PetalTrailClient.toolOn);
		y += 24;
		toggle(x, y, "Explosion de petales a la mort", () -> PetalTrailClient.killOn, () -> PetalTrailClient.killOn = !PetalTrailClient.killOn);
		y += 24;
		toggle(x, y, "Eclair a la mort", () -> PetalTrailClient.lightningOn, () -> PetalTrailClient.lightningOn = !PetalTrailClient.lightningOn);
	}

	private void toggle(int x, int y, String label, BooleanSupplier get, Runnable flip) {
		addRenderableWidget(Button.builder(Component.literal(label + " : " + (get.getAsBoolean() ? "OUI" : "NON")), b -> {
			flip.run();
			this.rebuildWidgets();
		}).bounds(x, y, 240, 20).build());
	}

	@Override
	public void removed() {
		PetalTrailClient.saveSettings();
		super.removed();
	}
}

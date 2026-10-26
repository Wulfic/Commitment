package com.wulfic.commitment.client;

import com.wulfic.commitment.Commitment;
import com.wulfic.commitment.lock.UnlockTimer;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The refusal the world list shows for a committed world that can't open (C.4): what changed, Back, and the way out,
 * which is the 24-hour unlock. Built from vanilla widgets and layouts only, never drawing by hand, because the draw
 * method itself differs between nodes ({@code render} on 1.21.11, {@code extractRenderState} on 26.x).
 */
public final class LockedScreen extends Screen {
	/** Button keys, which the client GameTest clicks by. */
	public static final String START = "commitment.locked.start";
	public static final String CANCEL = "commitment.locked.cancel";
	public static final String OPEN = "commitment.locked.open";
	public static final String RETRY = "commitment.locked.retry";
	public static final String SHOWN_LOG = "Commitment: locked screen shows {} lines; buttons: {}";
	private static final int TEXT_WIDTH = 360;
	private static final int BUTTON_WIDTH = 150;

	/** What the unlock buttons do. {@link ClientGate} runs the work off the render thread, then checks the world again. */
	public interface Actions {
		void startUnlock();

		void cancelUnlock();

		void recheck();
	}

	private final List<Component> lines;
	/** Null for a world that couldn't be checked: Back is the only way, and {@link #actions} is null too. */
	private final UnlockTimer.Status timer;
	private final long readAtNanos = System.nanoTime();
	private final Runnable back;
	private final Actions actions;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private ScrollableLayout scroll;
	private MultiLineTextWidget timerLine;
	private Button timerButton;
	private String shownLeft;
	private boolean due;

	private LockedScreen(Component title, List<Component> lines, UnlockTimer.Status timer, Runnable back, Actions actions) {
		super(title);
		this.lines = List.copyOf(lines);
		this.timer = timer;
		this.back = back;
		this.actions = actions;
	}

	/** A committed world whose setup changed, or whose lock record can't be trusted. */
	public static LockedScreen locked(List<String> changes, List<String> notices, UnlockTimer.Status timer, Runnable back,
			Actions actions) {
		List<Component> lines = new ArrayList<>();
		notices.forEach(n -> lines.add(Component.literal(n)));
		changes.forEach(c -> lines.add(Component.literal(c)));
		lines.add(Component.translatable("commitment.locked.hint"));
		return new LockedScreen(Component.translatable("commitment.locked.title"), lines, timer, back, actions);
	}

	/** A world whose lock couldn't be read, or couldn't be lifted: it stays shut (fail closed) and says why. */
	public static LockedScreen failed(String reason, Runnable back) {
		return new LockedScreen(Component.translatable("commitment.locked.failed.title"),
				List.of(Component.literal(reason), Component.translatable("commitment.locked.failed.closed")), null, back, null);
	}

	@Override
	protected void init() {
		layout.addTitleHeader(title, font);
		LinearLayout body = LinearLayout.vertical().spacing(6);
		body.defaultCellSetting().alignHorizontallyCenter();
		int textWidth = Math.min(width - 40, TEXT_WIDTH);
		for (Component line : lines) {
			body.addChild(new MultiLineTextWidget(line, font).setMaxWidth(textWidth).setCentered(true));
		}
		if (timer != null) {
			timerLine = body.addChild(new MultiLineTextWidget(timerText(), font).setMaxWidth(textWidth).setCentered(true));
		}
		scroll = layout.addToContents(new ScrollableLayout(minecraft, body, layout.getContentHeight()));
		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).width(BUTTON_WIDTH).build());
		switch (timer) {
			case null -> {
				// A world that couldn't be checked: Back is all there is.
			}
			case UnlockTimer.Status.Locked() -> footer.addChild(button(START, this::confirmStart));
			case UnlockTimer.Status.Pending running -> timerButton = footer.addChild(button(CANCEL, () -> {
				if (due) {
					actions.recheck();
				} else {
					actions.cancelUnlock();
				}
			}));
			case UnlockTimer.Status.Unverified unverified -> {
				footer.addChild(button(CANCEL, actions::cancelUnlock));
				footer.addChild(button(RETRY, actions::recheck));
			}
			case UnlockTimer.Status.Lifted() -> footer.addChild(button(OPEN, actions::recheck));
		}
		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
		// The labels as the player reads them: a key showing here means the lang file didn't load on this loader.
		List<String> labels = new ArrayList<>();
		footer.visitWidgets(w -> labels.add(w.getMessage().getString()));
		Commitment.LOGGER.info(SHOWN_LOG, scroll.getHeight() > 0 ? lines.size() + (timer != null ? 1 : 0) : 0, String.join(", ", labels));
	}

	/**
	 * The scroll area takes the body's height capped at the space between header and footer, so the body has to be
	 * arranged (sized) first: before that its height is 0, and so would the scroll area's be. Vanilla's own screens
	 * arrange twice for the same reason.
	 */
	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		scroll.setMaxHeight(Math.max(font.lineHeight, layout.getContentHeight() - 8));
		layout.arrangeElements();
	}

	/** The countdown moves on screen; at zero its button opens the world, and the gate checks internet time first. */
	@Override
	public void tick() {
		if (!(timer instanceof UnlockTimer.Status.Pending pending)) {
			return;
		}
		Duration left = UnlockTimer.left(pending.remaining(), System.nanoTime() - readAtNanos);
		if (left.isZero() && !due) {
			due = true;
			timerButton.setMessage(Component.translatable(OPEN));
		}
		String text = UnlockTimer.remaining(left);
		if (!text.equals(shownLeft)) {
			timerLine.setMessage(timerText());
			repositionElements();
		}
	}

	@Override
	public void onClose() {
		back.run();
	}

	private void confirmStart() {
		minecraft.setScreenAndShow(new ConfirmScreen(yes -> {
			if (yes) {
				actions.startUnlock();
			} else {
				minecraft.setScreenAndShow(this);
			}
		}, Component.translatable("commitment.locked.confirm.title"), Component.translatable("commitment.locked.confirm.text")));
	}

	private Component timerText() {
		ZoneId zone = ZoneId.systemDefault();
		return switch (timer) {
			case UnlockTimer.Status.Locked() -> Component.translatable("commitment.locked.no_timer");
			case UnlockTimer.Status.Pending p -> {
				Duration left = UnlockTimer.left(p.remaining(), System.nanoTime() - readAtNanos);
				shownLeft = UnlockTimer.remaining(left);
				yield left.isZero()
						? Component.translatable("commitment.locked.due")
						: Component.translatable("commitment.locked.pending", UnlockTimer.when(p.availableAt(), zone), shownLeft);
			}
			case UnlockTimer.Status.Unverified u ->
					Component.translatable("commitment.locked.unverified", UnlockTimer.when(u.availableAt(), zone), u.reason());
			case UnlockTimer.Status.Lifted() -> Component.translatable("commitment.locked.due");
		};
	}

	private static Button button(String key, Runnable press) {
		return Button.builder(Component.translatable(key), b -> press.run()).width(BUTTON_WIDTH).build();
	}
}

package geiler.addons.client.macro;

/** Client-thread synthetic key state retained between vanilla input polls. */
public final class MacroKeyHoldState {
	private int keyCode = -1;
	private long releaseAtNanos;
	private boolean scheduled;

	public void begin(int keyCode, long releaseAtNanos) {
		this.keyCode = keyCode;
		this.releaseAtNanos = releaseAtNanos;
		scheduled = keyCode >= 0;
	}

	public boolean isScheduled() {
		return scheduled;
	}

	public boolean isDown(int queriedKeyCode, long nowNanos) {
		return scheduled && keyCode == queriedKeyCode && nowNanos < releaseAtNanos;
	}

	public boolean isDue(long nowNanos) {
		return scheduled && nowNanos >= releaseAtNanos;
	}

	public int keyCode() {
		return keyCode;
	}

	public void clear() {
		keyCode = -1;
		releaseAtNanos = 0;
		scheduled = false;
	}
}

package com.dre.brewery.lore;

import com.dre.brewery.BreweryPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.util.List;

/**
 * A Scramble Stream that uses XOR operations to unscramble an inputstream.
 * <p>a byte generator feeded with the seed is used as xor source
 * <p>Used to unscramble data generated by the XORScrambleStream
 */
public class XORUnscrambleStream extends FilterInputStream {

	private long seed;
	private final List<Long> prevSeeds;
	private SeedInputStream xorStream;
	private boolean running;
	private boolean markRunning;
	private boolean markxor;

	private SuccessType successType = SuccessType.NONE;

	/**
	 * Create a new instance of an XORUnscrambler, unscrambling the given inputstream.
	 *
	 * @param in The Inputstream to be unscrambled
	 * @param seed The seed used for unscrambling
	 */
	public XORUnscrambleStream(InputStream in, long seed) {
		super(in);
		this.seed = seed;
		prevSeeds = null;
	}

	/**
	 * Create a new instance of an XORUnscrambler, unscrambling the given inputstream.
	 * <p>If given a List of previous Seeds, the unscrambler will try all of them for unscrambling the stream in case the seed fails.
	 *
	 * @param in The Inputstream to be unscrambled
	 * @param seed The seed used for unscrambling
	 * @param prevSeeds List of previously used seeds
	 */
	public XORUnscrambleStream(InputStream in, long seed, List<Long> prevSeeds) {
		super(in);
		this.seed = seed;
		this.prevSeeds = prevSeeds;
	}

	/**
	 * Before unscrambling, this has to be called to tell the unscrambler that scrambled data will follow.
	 * <br>Before starting the unscrambler, any data will just be passed through unmodified to the underlying stream.
	 * <br>The Unscrambling can be started and stopped arbitrarily at any point, allowing for parts of already unscrambled data in the stream.
	 *
	 * @throws IOException IOException
	 * @throws InvalidKeyException If the scrambled data could not be read, very likely caused by a wrong seed. Thrown after checking all previous seeds.
	 */
	public void start() throws IOException, InvalidKeyException {
		running = true;
		if (xorStream == null) {
			short id = (short) (in.read() << 8 | in.read());
			if (id == 0) {
				running = false;
				successType = SuccessType.UNSCRAMBLED;
				BreweryPlugin.getInstance().debugLog("Unscrambled data");
				return;
			}
			int parity = in.read();
			xorStream = new SeedInputStream(seed ^ id);
			boolean success = checkParity(parity);
			if (success) {
				successType = SuccessType.MAIN_SEED;
				BreweryPlugin.getInstance().debugLog("Using main Seed to unscramble");
			}

			if (!success && prevSeeds != null) {
				for (int i = prevSeeds.size() - 1; i >= 0; i--) {
					seed = prevSeeds.get(i);
					xorStream = new SeedInputStream(seed ^ id);
					if (success = checkParity(parity)) {
						successType = SuccessType.PREV_SEED;
						BreweryPlugin.getInstance().debugLog("Had to use prevSeed to unscramble");
						break;
					}
				}
			}
			if (!success) {
				throw new InvalidKeyException("Could not read scrambled data, is the seed wrong?");
			}
		}
	}

	private boolean checkParity(int parity) {
		return ((parity ^ xorStream.read()) & 0xFF) == ((int) (seed >> 48) & 0xFF); // Parity/Sanity
	}

	/**
	 * Stop the unscrambling, any following data will be passed through unmodified.
	 * <p>The unscrambling can be started again at any point after calling this
	 */
	public void stop() {
		running = false;
	}

	@Override
	public int read() throws IOException {
		if (!running) {
			return in.read();
		}
		return (in.read() ^ xorStream.read()) & 0xFF;
	}

	@Override
	public int read(@NotNull byte[] b, int off, int len) throws IOException {
		if (!running) {
			return in.read(b, off, len);
		}
		len = in.read(b, off, len);
		for (int i = off; i < len + off; i++) {
			b[i] ^= xorStream.read();
		}
		return len;
	}

	/**
	 * What was used to unscramble the stream: it was already unscrambled | Main Seed | Prev Seed
	 *
	 * @return The Type of Seed used to unscramble this, if any
	 */
	public SuccessType getSuccessType() {
		return successType;
	}

	@SuppressWarnings("ResultOfMethodCallIgnored")
	@Override
	public long skip(long n) throws IOException {
		long skipped = in.skip(n);
		if (running && skipped > 0) {
			xorStream.skip(skipped);
		}
		return skipped;
	}

	@Override
	public void close() throws IOException {
		if (xorStream != null) {
			xorStream.close();
			xorStream = null;
		}
		running = false;
		super.close();
	}

	@Override
	public boolean markSupported() {
		return in.markSupported();
	}

	@Override
	public synchronized void reset() throws IOException {
		in.reset();
		if (markxor) {
			xorStream.reset();
		} else {
			xorStream = null;
		}
		running = markRunning;
	}

	@Override
	public synchronized void mark(int readlimit) {
		in.mark(readlimit);
		if (xorStream != null) {
			xorStream.mark(readlimit);
			markxor = true;
		}
		markRunning = running;
	}

	/**
	 * What succeeded in unscrambling the Stream.
	 */
	public static enum SuccessType {
		/**
		 * The Stream was already unscrambled.
		 */
		UNSCRAMBLED,

		/**
		 * The Main Seed was used to unscramble the Stream.
		 */
		MAIN_SEED,

		/**
		 * One of the Previous Seeds was used to unscramble the Stream.
		 */
		PREV_SEED,

		/**
		 * It was not successful.
		 */
		NONE;
	}
}

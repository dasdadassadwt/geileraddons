package geiler.addons.client.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Reads an HTTP response body with both a strict byte cap and an end-to-end deadline. */
public final class BoundedHttpBodyReader {
	private BoundedHttpBodyReader() { }

	public static HttpResponse<byte[]> send(HttpClient client, HttpRequest request, int maximumBytes,
		Duration deadline) throws IOException, InterruptedException {
		if (client == null || request == null || maximumBytes < 0 || deadline == null
			|| deadline.isZero() || deadline.isNegative()) throw new IllegalArgumentException("Invalid HTTP body limits");
		HttpResponse.BodyHandler<byte[]> handler = ignored -> new LimitedSubscriber(maximumBytes);
		CompletableFuture<HttpResponse<byte[]>> response = client.sendAsync(request, handler);
		try {
			return response.get(Math.max(1L, deadline.toMillis()), TimeUnit.MILLISECONDS);
		} catch (TimeoutException timeout) {
			response.cancel(true);
			throw new HttpTimeoutException("HTTP response body exceeded its " + deadline.toSeconds() + " second deadline");
		} catch (ExecutionException failed) {
			Throwable cause = failed.getCause();
			if (cause instanceof IOException io) throw io;
			if (cause instanceof RuntimeException runtime) throw runtime;
			throw new IOException("Could not read HTTP response body", cause);
		} catch (InterruptedException interrupted) {
			response.cancel(true);
			throw interrupted;
		}
	}

	private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
		private static final int COPY_BUFFER_SIZE = 8 * 1024;
		private final int maximumBytes;
		private final ByteArrayOutputStream output;
		private final CompletableFuture<byte[]> body = new CompletableFuture<>();
		private final byte[] copyBuffer = new byte[COPY_BUFFER_SIZE];
		private Flow.Subscription subscription;
		private int received;

		private LimitedSubscriber(int maximumBytes) {
			this.maximumBytes = maximumBytes;
			output = new ByteArrayOutputStream(Math.min(maximumBytes, COPY_BUFFER_SIZE));
		}

		@Override
		public CompletionStage<byte[]> getBody() {
			return body;
		}

		@Override
		public void onSubscribe(Flow.Subscription next) {
			if (subscription != null) {
				next.cancel();
				return;
			}
			subscription = next;
			next.request(1);
		}

		@Override
		public void onNext(List<ByteBuffer> buffers) {
			if (body.isDone()) return;
			try {
				for (ByteBuffer source : buffers) {
					while (source.hasRemaining()) {
						int remaining = maximumBytes - received;
						if (remaining <= 0) throw new IOException("HTTP response exceeded " + maximumBytes + " bytes");
						int count = Math.min(source.remaining(), Math.min(copyBuffer.length, remaining));
						source.get(copyBuffer, 0, count);
						output.write(copyBuffer, 0, count);
						received += count;
					}
				}
				subscription.request(1);
			} catch (IOException error) {
				subscription.cancel();
				body.completeExceptionally(error);
			}
		}

		@Override
		public void onError(Throwable error) {
			body.completeExceptionally(error);
		}

		@Override
		public void onComplete() {
			body.complete(output.toByteArray());
		}
	}
}

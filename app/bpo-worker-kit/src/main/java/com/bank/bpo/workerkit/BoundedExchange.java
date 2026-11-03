package com.bank.bpo.workerkit;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One HTTP exchange that ends within a deadline, body included. {@link HttpRequest#timeout()} stops counting once
 * the response headers arrive, so a peer that sends headers and then stalls would hold the calling job thread
 * forever; waiting on the async exchange with a deadline, and cancelling it, bounds the whole exchange.
 */
final class BoundedExchange {

    /** The exchange did not finish within its deadline. */
    static final class Timeout extends Exception {
        Timeout(Throwable cause) {
            super("no complete answer within the deadline", cause);
        }
    }

    private BoundedExchange() {
    }

    static HttpResponse<String> send(HttpClient client, HttpRequest request, Duration deadline)
        throws IOException, InterruptedException, Timeout {
        CompletableFuture<HttpResponse<String>> exchange = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return exchange.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new Timeout(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.net.http.HttpTimeoutException) {
                throw new Timeout(cause);
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException(cause);
        } finally {
            exchange.cancel(true);
        }
    }
}

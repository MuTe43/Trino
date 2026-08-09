package tn.sncft.trino.diffusion;

import java.util.List;

/**
 * Frame wrapper for the multiplexed stream: carries the channel identities
 * alongside the delta so a client receiving several channels over one
 * connection can route each frame without guessing.
 *
 * <p>{@code canaux} is a list, not a single channel, and that is load-bearing.
 * A course publishes to its ligne and to every gare it has not yet cleared, so
 * one delta legitimately concerns several of a client's channels at once. An
 * earlier version sent the frame once tagged with only the first channel that
 * matched; a page showing both the map ({@code ligne:1}) and a station board
 * ({@code gare:7}) then had the board's handlers silently skipped, because the
 * client routes on this field. Sending every matching channel keeps the delta
 * single -- the point of multiplexing -- while still reaching each consumer
 * exactly once.
 */
public record EnveloppeSse(List<String> canaux, Object donnees) {
}

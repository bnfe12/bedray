package io.github.andrexarlay.vulkanbridge.api;

/**
 * Result of {@link FramePhases#claim}.
 *
 * @param role      the role asked for
 * @param requester the mod id that asked
 * @param granted   true when {@code requester} holds the role after the call (first claim, or a repeated claim by the owner)
 * @param owner     the mod id that holds the role after the call ({@code requester} when granted)
 */
@Experimental
public record ClaimResult(ExclusiveRole role, String requester, boolean granted, String owner) {
}

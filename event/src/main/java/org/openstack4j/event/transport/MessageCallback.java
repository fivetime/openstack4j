package org.openstack4j.event.transport;

/**
 * Callback for receiving raw message bytes from a transport.
 *
 * <p>Implementations must be thread-safe as messages may arrive
 * concurrently from multiple service consumers.</p>
 */
@FunctionalInterface
public interface MessageCallback {

    /**
     * Called when a raw message is received from the broker.
     *
     * @param serviceName  OpenStack service that produced the notification
     * @param body         raw message bytes (oslo.messaging envelope or raw notification)
     */
    void onMessage(String serviceName, byte[] body);
}

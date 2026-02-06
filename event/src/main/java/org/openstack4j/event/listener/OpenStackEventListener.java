package org.openstack4j.event.listener;

import org.openstack4j.event.model.OpenStackEvent;

/**
 * Callback interface for receiving OpenStack notification events.
 *
 * <p>Replaces Spring's {@code @EventListener} mechanism. Register implementations
 * with {@link OpenStackEventManager}.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * manager.addListener(event -> {
 *     if (event.getResourceType() == ResourceType.SERVER) {
 *         System.out.println("Server " + event.getResourceId() + " → " + event.getStatus());
 *     }
 * });
 * </pre>
 */
@FunctionalInterface
public interface OpenStackEventListener {

    /**
     * Called when an OpenStack notification event is received and parsed.
     *
     * <p>Implementations should be thread-safe as events may arrive
     * concurrently from multiple vhost consumers.</p>
     *
     * @param event parsed OpenStack event
     */
    void onEvent(OpenStackEvent event);
}

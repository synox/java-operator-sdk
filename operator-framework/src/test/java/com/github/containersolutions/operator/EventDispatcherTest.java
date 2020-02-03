package com.github.containersolutions.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.containersolutions.operator.api.Controller;
import com.github.containersolutions.operator.api.ResourceController;
import com.github.containersolutions.operator.processing.EventDispatcher;
import com.github.containersolutions.operator.sample.TestCustomResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.CustomResourceDoneable;
import io.fabric8.kubernetes.client.CustomResourceList;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.Replaceable;
import io.fabric8.kubernetes.client.dsl.internal.CustomResourceOperationsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.HashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class EventDispatcherTest {

    private CustomResource testCustomResource;
    private EventDispatcher<CustomResource> eventDispatcher;
    private ResourceController<CustomResource> resourceController = mock(ResourceController.class);
    private CustomResourceOperationsImpl<CustomResource, CustomResourceList<CustomResource>,
            CustomResourceDoneable<CustomResource>> resourceOperation = mock(CustomResourceOperationsImpl.class);

    @BeforeEach
    void setup() {
        eventDispatcher = new EventDispatcher(resourceController, resourceOperation,
                Controller.DEFAULT_FINALIZER);

        testCustomResource = getResource();

        when(resourceController.createOrUpdateResource(eq(testCustomResource))).thenReturn(Optional.of(testCustomResource));
        when(resourceController.deleteResource(eq(testCustomResource))).thenReturn(true);
        when(resourceOperation.lockResourceVersion(any())).thenReturn(mock(Replaceable.class));
    }

    @Test
    void callCreateOrUpdateOnNewResource() {
        eventDispatcher.handleEvent(Watcher.Action.ADDED, testCustomResource);
        verify(resourceController, times(1)).createOrUpdateResource(ArgumentMatchers.eq(testCustomResource));
    }

    @Test
    void callCreateOrUpdateOnModifiedResource() {
        eventDispatcher.handleEvent(Watcher.Action.MODIFIED, testCustomResource);
        verify(resourceController, times(1)).createOrUpdateResource(ArgumentMatchers.eq(testCustomResource));
    }

    @Test
    void adsDefaultFinalizerOnCreateIfNotThere() {
        eventDispatcher.handleEvent(Watcher.Action.MODIFIED, testCustomResource);
        verify(resourceController, times(1))
                .createOrUpdateResource(argThat(testCustomResource ->
                        testCustomResource.getMetadata().getFinalizers().contains(Controller.DEFAULT_FINALIZER)));
    }

    @Test
    void callsDeleteIfObjectHasFinalizerAndMarkedForDelete() {
        testCustomResource.getMetadata().setDeletionTimestamp("2019-8-10");
        testCustomResource.getMetadata().getFinalizers().add(Controller.DEFAULT_FINALIZER);

        eventDispatcher.handleEvent(Watcher.Action.MODIFIED, testCustomResource);

        verify(resourceController, times(1)).deleteResource(eq(testCustomResource));
    }

    /**
     * Note that there could be more finalizers. Out of our control.
     */
    @Test
    void callDeleteOnControllerIfMarkedForDeletionButThereIsNoDefaultFinalizer() {
        markForDeletion(testCustomResource);

        eventDispatcher.handleEvent(Watcher.Action.MODIFIED, testCustomResource);

        verify(resourceController).deleteResource(eq(testCustomResource));
    }

    @Test
    void removesDefaultFinalizerOnDelete() {
        markForDeletion(testCustomResource);
        testCustomResource.getMetadata().getFinalizers().add(Controller.DEFAULT_FINALIZER);

        eventDispatcher.handleEvent(Watcher.Action.MODIFIED, testCustomResource);

        assertEquals(0, testCustomResource.getMetadata().getFinalizers().size());
        verify(resourceOperation, times(1)).lockResourceVersion(any());
    }

    @Test
    void doesNotRemovesTheFinalizerIfTheDeleteMethodRemovesFalse() {
        when(resourceController.deleteResource(eq(testCustomResource))).thenReturn(false);
        markForDeletion(testCustomResource);
        testCustomResource.getMetadata().getFinalizers().add(Controller.DEFAULT_FINALIZER);

        eventDispatcher.handleEvent(Watcher.Action.MODIFIED, testCustomResource);

        assertEquals(1, testCustomResource.getMetadata().getFinalizers().size());
        verify(resourceOperation, never()).lockResourceVersion(any());
    }

    @Test
    void doesNotUpdateTheResourceIfEmptyOptionalReturned() {
        testCustomResource.getMetadata().getFinalizers().add(Controller.DEFAULT_FINALIZER);
        when(resourceController.createOrUpdateResource(eq(testCustomResource))).thenReturn(Optional.empty());

        eventDispatcher.handleEvent(Watcher.Action.MODIFIED, testCustomResource);

        verify(resourceOperation, never()).lockResourceVersion(any());
    }

    @Test
    void addsFinalizerIfNotMarkedForDeletionAndEmptyCustomResourceReturned() {
        when(resourceController.createOrUpdateResource(eq(testCustomResource))).thenReturn(Optional.empty());

        eventDispatcher.handleEvent(Watcher.Action.MODIFIED, testCustomResource);

        assertEquals(1, testCustomResource.getMetadata().getFinalizers().size());
        verify(resourceOperation, times(1)).lockResourceVersion(any());
    }

    @Test
    void doesNotAddFinalizerIfOptionalIsReturnedButMarkedForDeletion() {
        markForDeletion(testCustomResource);
        when(resourceController.createOrUpdateResource(eq(testCustomResource))).thenReturn(Optional.empty());

        eventDispatcher.handleEvent(Watcher.Action.MODIFIED, testCustomResource);

        assertEquals(0, testCustomResource.getMetadata().getFinalizers().size());
        verify(resourceOperation, never()).lockResourceVersion(any());
    }

    private void markForDeletion(CustomResource customResource) {
        customResource.getMetadata().setDeletionTimestamp("2019-8-10");
    }

    CustomResource getResource() {
        TestCustomResource resource = new TestCustomResource();
        resource.setMetadata(new ObjectMetaBuilder()
                .withClusterName("clusterName")
                .withCreationTimestamp("creationTimestamp")
                .withDeletionGracePeriodSeconds(10L)
                .withGeneration(10L)
                .withName("name")
                .withNamespace("namespace")
                .withResourceVersion("resourceVersion")
                .withSelfLink("selfLink")
                .withUid("uid").build());
        return resource;
    }

    HashMap getRawResource() {
        return new ObjectMapper().convertValue(getResource(), HashMap.class);
    }
}

package burp.ui.dnd;

import burp.ui.tree.RequestTreeDragPayload;
import burp.ui.tree.RequestTreeTransferHandler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DragDropDataFlavorTest {

    @Test
    void localObjectDataFlavorsInitializeWithPayloadRepresentationClasses() {
        assertThat(RequestTreeTransferHandler.TREE_PAYLOAD_FLAVOR.getRepresentationClass())
                .isEqualTo(RequestTreeDragPayload.class);
        assertThat(EnvironmentProfileDragSourceTransferHandler.ENVIRONMENT_PAYLOAD_FLAVOR.getRepresentationClass())
                .isEqualTo(EnvironmentDragPayload.class);
        assertThat(RunnerQueueTransferHandler.RUNNER_QUEUE_PAYLOAD_FLAVOR.getRepresentationClass())
                .isEqualTo(RunnerQueueDragPayload.class);
    }
}

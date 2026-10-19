package com.example.aicodebackend.core.builder.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 部署消息的 JSON 兼容性
 * 滚动升级时队列里可能有加 taskId 之前发出的旧消息；旧消息必须还能被消费（taskId=null）……
 */
class DeployTaskMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("升级前的旧消息（没有 taskId）仍能反序列化")
    void oldMessageWithoutTaskIdShouldStillDeserialize() throws Exception {
        String oldJson = "{\"appId\":1001,\"sequence\":2,\"operatorId\":2002,\"requestedAt\":123}";

        DeployTaskMessage message = objectMapper.readValue(oldJson, DeployTaskMessage.class);

        assertEquals(1001L, message.appId());
        assertEquals(2L, message.sequence());
        assertEquals(2002L, message.operatorId());
        assertNull(message.taskId(), "旧消息没有 taskId，应兼容为 null");
    }

    @Test
    @DisplayName("新消息的 taskId 能完整往返")
    void taskIdShouldSurviveJsonRoundTrip() throws Exception {
        DeployTaskMessage message = DeployTaskMessage.of(1001L, 2L, 2002L, "task-abc");

        DeployTaskMessage decoded = objectMapper.readValue(
                objectMapper.writeValueAsString(message), DeployTaskMessage.class);

        assertEquals("task-abc", decoded.taskId());
        assertEquals(message.sequence(), decoded.sequence());
    }
}

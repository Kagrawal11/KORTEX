package com.miniautomation.backend.recording;

import com.miniautomation.backend.ai.LlmClient;
import org.springframework.stereotype.Component;

@Component
public class ElementMetadataExtractor {

    private final LlmClient llmClient;

    public ElementMetadataExtractor(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public String generateAiDescription(CapturedEvent event) {
        return llmClient.generateElementDescription(
                event.getTag(),
                event.getElementId(),
                event.getName(),
                event.getType(),
                event.getRole(),
                event.getLabelText(),
                event.getPlaceholder()
        );
    }
}

package com.tradingBot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OptionChainResponse {

    @JsonProperty("options")
    private OptionsWrapper options;

    // For backward compatibility with existing code
    public List<Option> getOptionsList() {
        if (options != null && options.getOption() != null) {
            return options.getOption();
        }
        return new ArrayList<>();
    }

    // Convenience method to check if we have options
    public boolean hasOptions() {
        return options != null && options.getOption() != null && !options.getOption().isEmpty();
    }

    // Get options (alternative accessor)
    public List<Option> getOptions() {
        return getOptionsList();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OptionsWrapper {
        @JsonProperty("option")
        private List<Option> option;
    }
}
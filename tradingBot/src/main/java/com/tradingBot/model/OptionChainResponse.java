package com.tradingBot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OptionChainResponse {
    private OptionsWrapper options;

    public List<Option> getOptionsList() {
        return options != null && options.getOption() != null ? options.getOption() : new ArrayList<>();
    }
}
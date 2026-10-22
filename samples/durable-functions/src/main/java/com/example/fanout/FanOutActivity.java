package com.example.fanout;

import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.durabletask.azurefunctions.DurableActivityTrigger;

public class FanOutActivity {
    @FunctionName("FanOutActivity")
    public String fanOutActivity(
            @DurableActivityTrigger(name = "input") String input) {
        return "Processed " + input;
    }
}

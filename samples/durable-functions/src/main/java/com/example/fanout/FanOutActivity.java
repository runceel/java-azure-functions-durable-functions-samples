package com.example.fanout;

import java.util.Random;

import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.durabletask.azurefunctions.DurableActivityTrigger;

public class FanOutActivity {
    @FunctionName("FanOutActivity")
    public String fanOutActivity(
            @DurableActivityTrigger(name = "input") String input) throws InterruptedException {
        Thread.sleep(3000 + new Random().nextLong(3000));
        return "Processed " + input;
    }
}

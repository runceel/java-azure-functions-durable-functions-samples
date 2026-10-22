package com.example.hello;

import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.durabletask.azurefunctions.DurableActivityTrigger;

public class HelloActivities {
    @FunctionName("StepOne")
    public String stepOne(
            @DurableActivityTrigger(name = "input") String input) {
        return input + " -> step 1";
    }

    @FunctionName("StepTwo")
    public String stepTwo(
            @DurableActivityTrigger(name = "input") String input) {
        return input + " -> step 2";
    }

    @FunctionName("StepThree")
    public String stepThree(
            @DurableActivityTrigger(name = "input") String input) {
        return input + " -> step 3";
    }
}

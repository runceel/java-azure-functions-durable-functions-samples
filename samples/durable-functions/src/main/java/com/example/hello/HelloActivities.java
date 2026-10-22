package com.example.hello;

import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.durabletask.azurefunctions.DurableActivityTrigger;

/**
 * Hello の順次処理を構成する Activity Functions です。
 */
public class HelloActivities {
    /**
     * 開始文字列に最初の処理段階を追加します。
     *
     * @param input 前段から受け取った文字列
     * @return 処理段階 1 を適用した文字列
     */
    @FunctionName("StepOne")
    public String stepOne(
            @DurableActivityTrigger(name = "input") String input) {
        return input + " -> step 1";
    }

    /**
     * 前段の結果に処理段階 2 を追加します。
     *
     * @param input 前段の処理結果
     * @return 処理段階 2 を適用した文字列
     */
    @FunctionName("StepTwo")
    public String stepTwo(
            @DurableActivityTrigger(name = "input") String input) {
        return input + " -> step 2";
    }

    /**
     * 前段の結果に最後の処理段階を追加します。
     *
     * @param input 前段の処理結果
     * @return 最終的な処理結果
     */
    @FunctionName("StepThree")
    public String stepThree(
            @DurableActivityTrigger(name = "input") String input) {
        return input + " -> step 3";
    }
}

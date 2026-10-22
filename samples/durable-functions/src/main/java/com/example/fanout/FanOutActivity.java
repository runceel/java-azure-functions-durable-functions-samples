package com.example.fanout;

import java.util.Random;

import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.durabletask.azurefunctions.DurableActivityTrigger;

/**
 * fan-out 処理でアイテムを個別に処理する Activity Function です。
 */
public class FanOutActivity {
    /**
     * 処理時間をランダムに変化させて並列処理をシミュレートします。
     *
     * @param input 処理対象のアイテム名
     * @return 処理済みアイテムを示す文字列
     * @throws InterruptedException スリープ中にスレッドが割り込まれた場合
     */
    @FunctionName("FanOutActivity")
    public String fanOutActivity(
            @DurableActivityTrigger(name = "input") String input) throws InterruptedException {
        Thread.sleep(3000 + new Random().nextLong(3000));
        return "Processed " + input;
    }
}

package zixuan.modules.companion.capability.vo;

import java.util.List;

import lombok.Data;

@Data
public class CapabilityParityVO {
    private long checkedDevices;
    private long mismatchedDevices;
    private List<String> mismatches = List.of();

    public boolean isReady() {
        return mismatchedDevices == 0;
    }
}

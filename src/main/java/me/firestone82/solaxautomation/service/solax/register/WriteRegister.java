package me.firestone82.solaxautomation.service.solax.register;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.solax.model.InverterMode;

@Slf4j
public class WriteRegister<T> extends Register<T> {
    public static WriteRegister<Integer> UNLOCK_PASSWORD = new WriteRegister<>("UnlockPassword", 0x0000, 1, Integer.class);
    public static WriteRegister<InverterMode> USE_MODE = new WriteRegister<>("SolarChargerUseMode", 0x001F, 1, InverterMode.class);
    public static WriteRegister<Integer> EXPORT_LIMIT = new WriteRegister<>("ExportLimit", 0x0042, 1, Integer.class);

    public WriteRegister(String name, int address, int length, Class<T> tClass) {
        super(name, address, length, tClass);
    }
}

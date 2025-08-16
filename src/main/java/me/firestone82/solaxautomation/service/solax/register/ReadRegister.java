package me.firestone82.solaxautomation.service.solax.register;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.solax.model.InverterMode;
import me.firestone82.solaxautomation.service.solax.model.ReadRegistryType;

@Getter
@Slf4j
public class ReadRegister<T> extends Register<T> {
    public static ReadRegister<String> INVERTER_SN = new ReadRegister<>("Inverter SN", 0x0000, 7, String.class, ReadRegistryType.HOLDING);
    public static ReadRegister<Integer> EXPORT_LIMIT = new ReadRegister<>("ExportLimit", 0x00B6, 1, Integer.class, ReadRegistryType.HOLDING);
    public static ReadRegister<InverterMode> USE_MODE = new ReadRegister<>("SolarChargerUseMode", 0x008B, 1, InverterMode.class, ReadRegistryType.HOLDING);
    public static ReadRegister<Integer[]> POWER_DC = new ReadRegister<>("PowerDC", 0x000A, 2, Integer[].class, ReadRegistryType.INPUT);
    public static ReadRegister<Integer> BATTERY_CAPACITY = new ReadRegister<>("BatteryCapacity", 0x001C, 1, Integer.class, ReadRegistryType.INPUT);
    public static ReadRegister<Integer> LOCK_STATE = new ReadRegister<>("LockState", 0x0054, 1, Integer.class, ReadRegistryType.INPUT);
    public static ReadRegister<Integer> POWER_CONTROL = new ReadRegister<>("ModbusPowerControl", 0x0100, 1, Integer.class, ReadRegistryType.INPUT);
    public static ReadRegister<Integer> BMS_USER_SOC = new ReadRegister<>("BMS_UserSOC", 0x00BE, 1, Integer.class, ReadRegistryType.INPUT);
    public static ReadRegister<Integer> BMS_USER_SOH = new ReadRegister<>("BMS_UserSOH", 0x00BF, 1, Integer.class, ReadRegistryType.INPUT);

    private final ReadRegistryType type;

    public ReadRegister(String name, int address, int count, Class<T> tClass, ReadRegistryType registryType) {
        super(name, address, count, tClass);
        this.type = registryType;
    }
}

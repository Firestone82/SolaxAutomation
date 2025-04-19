package me.firestone82.solaxautomation.service.solax.client;

import net.solarnetwork.io.modbus.ModbusByteUtils;

public class ModbusConvertUtil {

    @SuppressWarnings("unchecked")
    public static <T> T convertResponse(byte[] data, Class<T> tClass, int count) {
        if (tClass == String.class) {
            StringBuilder result = new StringBuilder();

            for (byte datum : data) {
                char c = (char) datum;
                result.append(c);
            }

            return (T) result.toString();
        }

        if (tClass == Byte.class) {
            return (T) Byte.valueOf(data[0]);
        }

        if (tClass == Integer.class) {
            short[] decoded = ModbusByteUtils.decode(data);
            return (T) Integer.valueOf(decoded[0]);
        }

        if (tClass == Integer[].class) {
            int[] decoded = ModbusByteUtils.decodeUnsigned(data);

            Integer[] result = new Integer[count];
            for (int i = 0; i < count; i++) {
                result[i] = decoded[i];
            }

            return (T) result;
        }

        if (tClass.isEnum()) {
            short[] decoded = ModbusByteUtils.decode(data);
            int ordinal = decoded[0];

            Object[] constants = tClass.getEnumConstants();
            if (ordinal < 0 || ordinal >= constants.length) {
                throw new IllegalArgumentException("Invalid ordinal " + ordinal + " for enum " + tClass.getName());
            }

            return (T) constants[ordinal];
        }

        throw new IllegalStateException("Unable to convert response data to type: " + tClass.getName());
    }

    public static <T> short[] convertRequest(T payload, int count) {
        switch (payload) {
            case String value -> {
                byte[] bytes = value.getBytes();
                short[] result = new short[count];

                for (int i = 0; i < count; i++) {
                    if (i < bytes.length) {
                        result[i] = (short) (bytes[i] & 0xFF);
                    } else {
                        result[i] = 0;
                    }
                }

                return result;
            }

            case Byte value -> {
                return new short[]{(short) (value & 0xFF)};
            }

            case Integer value -> {
                return new short[]{(short) (value & 0xFFFF)};
            }

            default -> throw new IllegalStateException("Unexpected value: " + payload);
        }
    }
}

package org.apache.tvm;

/**
 * TVM Device stub — actual implementation is loaded at runtime
 * from the native .so libraries via JNI.
 */
public class Device {
    public int deviceType;
    public int deviceId;

    public Device(int deviceType, int deviceId) {
        this.deviceType = deviceType;
        this.deviceId = deviceId;
    }

    public static Device opencl() {
        return new Device(4, 0);
    }

    public static Device vulkan() {
        return new Device(7, 0);
    }

    public static Device cpu() {
        return new Device(1, 0);
    }
}

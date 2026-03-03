package org.apache.tvm;

/**
 * TVM TVMValue stub — actual implementation is loaded at runtime via JNI.
 */
public class TVMValue {
    public Module asModule() {
        throw new UnsupportedOperationException("TVM native not loaded");
    }

    public String asString() {
        throw new UnsupportedOperationException("TVM native not loaded");
    }

    public long asLong() {
        throw new UnsupportedOperationException("TVM native not loaded");
    }

    public double asDouble() {
        throw new UnsupportedOperationException("TVM native not loaded");
    }
}

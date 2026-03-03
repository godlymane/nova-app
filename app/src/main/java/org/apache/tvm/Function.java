package org.apache.tvm;

/**
 * TVM Function stub — actual implementation is loaded at runtime via JNI.
 */
public class Function {
    public interface Callback {
        Object invoke(TVMValue... args);
    }

    public static Function getFunction(String name) {
        throw new UnsupportedOperationException("TVM native not loaded");
    }

    public static Function convertFunc(Callback callback) {
        throw new UnsupportedOperationException("TVM native not loaded");
    }

    public TVMValue invoke() {
        throw new UnsupportedOperationException("TVM native not loaded");
    }

    public Function pushArg(int arg) {
        return this;
    }

    public Function pushArg(String arg) {
        return this;
    }

    public Function pushArg(Function arg) {
        return this;
    }
}

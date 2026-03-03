package org.apache.tvm;

/**
 * TVM Module stub — actual implementation is loaded at runtime via JNI.
 */
public class Module {
    public Function getFunction(String name) {
        throw new UnsupportedOperationException("TVM native not loaded");
    }
}

package dev.dennis.osfx.inject.hook;

import java.util.Map;

public class ClassHook {
    private final String name;
    private final Map<String, FieldHook> fields;
    private final Map<String, MethodHook> methods;

    public ClassHook(String name, Map<String, FieldHook> fields, Map<String, MethodHook> methods) {
        this.name = name;
        this.fields = fields;
        this.methods = methods;
    }

    @Override
    public String toString() {
        return "ClassHook{" +
                "name='" + name + '\'' +
                ", fields=" + fields +
                ", methods=" + methods +
                '}';
    }

    public FieldHook getField(String name) {
        return fields.get(name);
    }

    public MethodHook getMethod(String name) {
        return methods.get(name);
    }

    public String getName() {
        return name;
    }

    public Map<String, FieldHook> getFields() {
        return fields;
    }

    public Map<String, MethodHook> getMethods() {
        return methods;
    }
}

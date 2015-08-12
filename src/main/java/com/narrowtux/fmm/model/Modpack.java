package com.narrowtux.fmm.model;

import com.google.gson.*;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import org.sat4j.core.VecInt;
import org.sat4j.minisat.SolverFactory;
import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.ISolver;

import javax.xml.crypto.Data;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class Modpack {
    private static final int RECURSION_LIMIT = 20;

    private StringProperty name = new SimpleStringProperty();
    private ObservableSet<ModReference> mods = FXCollections.observableSet(new LinkedHashSet<ModReference>());
    private ObjectProperty<Path> path = new SimpleObjectProperty<>();

    public Modpack(String name, Path path) {
        setName(name);
        setPath(path);

        nameProperty().addListener((obs, ov, nv) -> {
            if (nv == null || nv.isEmpty() || nv.contains("/")) {
                setName(ov);
                return;
            }
            Path newPath = getPath().getParent().resolve(nv);
            try {
                Files.move(getPath(), newPath);
                setPath(newPath);
                for (ModReference mod : getMods()) {
                    String fileName = mod.getMod().getPath().getFileName().toString();
                    Path modNewPath = mod.getMod().getPath().getParent().getParent().resolve(nv).resolve(fileName);
                    mod.getMod().setPath(modNewPath);
                }
            } catch (IOException e) {
                e.printStackTrace();
                setName(ov);
            }
        });
    }

    public String getName() {
        return name.get();
    }

    public void setName(String name) {
        this.name.set(name);
    }

    public StringProperty nameProperty() {
        return name;
    }

    public Path getPath() {
        return path.get();
    }

    public ObjectProperty<Path> pathProperty() {
        return path;
    }

    public void setPath(Path path) {
        this.path.set(path);
    }

    public ObservableSet<ModReference> getMods() {
        return mods;
    }

    @Override
    public String toString() {
        return "Modpack{" +
                "name=" + getName() +
                ", mods=" + mods +
                '}';
    }

    public void writeModList() {
        writeModList(false);
    }

    public void writeModList(boolean writeVersion) {
        writeModList(getPath().resolve("mod-list.json"), writeVersion, getMods().toArray(new ModReference[0]));
    }

    public static void writeModList(Path file, boolean writeVersion, ModReference ... mods) {
        JsonObject root = new JsonObject();
        JsonArray modList = new JsonArray();
        JsonObject baseMod = new JsonObject();
        baseMod.addProperty("name", "base");
        baseMod.addProperty("enabled", true);
        modList.add(baseMod);
        for (ModReference mod : mods) {
            JsonObject modInfo = new JsonObject();
            modInfo.addProperty("name", mod.getMod().getName());
            modInfo.addProperty("enabled", mod.getEnabled());
            if (writeVersion) {
                modInfo.addProperty("version", mod.getMod().getVersion().toString());
            }
            modList.add(modInfo);
        }
        root.add("mods", modList);
        try {
            Gson gson = new Gson();
            String json = gson.toJson(root);
            FileWriter writer = new FileWriter(file.toFile());
            writer.write(json);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Set<Mod> resolveDependencies() {
        Set<Mod> confirmedMods = getMods().stream().map(ModReference::getMod).collect(Collectors.toSet());
        try {
            return resolveDependencies(0, confirmedMods);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public Set<Mod> resolveDependencies(int recursionInterval, Set<Mod> confirmedMods) throws Exception {
        if (recursionInterval > RECURSION_LIMIT) {
            throw new Exception("Recursion limit reached");
        }

        recursionInterval ++;

        List<ModDependency> deps = new LinkedList<>();
        confirmedMods.forEach(mod -> deps.addAll(mod.getDependencies().stream().filter(dep -> !dep.getOptional()).collect(Collectors.toList())));
        System.out.println(getName() + " dependencies: " + deps);
        for (Mod mod : confirmedMods) {
            List<ModDependency> satisfied = new LinkedList<>();
            for (ModDependency dep : deps) {
                if (dep.getDependencyName().equals(mod.getName())) {
                    if (dep.getMatchedVersion() == null || dep.getMatchedVersion().matches(mod.getVersion())) {
                        satisfied.add(dep);
                    }
                }
            }
            deps.removeAll(satisfied);
        }
        if (deps.isEmpty()) {
            System.out.println(getName() + " No dependencies missing");
            return confirmedMods;
        }
        ISolver solver = SolverFactory.newDefault();

        Set<String> names = deps.stream().map(ModDependency::getDependencyName).collect(Collectors.toSet());
        List<Mod> matchedMods = new LinkedList<>();
        Datastore store = Datastore.getInstance();

        for (int interval = 0; interval < RECURSION_LIMIT; interval++) {
            final boolean[] modNotFound = {false};
            deps.stream().forEach(dep -> {
                final int[] found = {0};
                VecInt clause = new VecInt();
                store.getMods().values().stream()
                        .filter(mod -> dep.getMatchedVersion() == null || dep.getMatchedVersion().matches(mod.getVersion()))
                        .filter(mod -> dep.getDependencyName().equals(mod.getName()))
                        .forEach(mod -> {
                            found[0]++;
                            if (!matchedMods.contains(mod)) {
                                matchedMods.add(mod);
                            }
                            int literal = matchedMods.indexOf(mod) + 1;
                            if (!clause.contains(literal)) {
                                clause.push(literal);
                            }
                        });
                if (found[0] == 0) {
                    modNotFound[0] = true;
                    System.out.println(getName() + " couldn't find mod for " + dep);
                }
                try {
                    solver.addClause(clause);
                } catch (ContradictionException e) {
                    e.printStackTrace();
                }
            });
            if (modNotFound[0]) {
                System.out.println(getName() + " returning null because one or more mods couldn't be found");
                return null;
            }
            System.out.println(getName() + " matched mods: " + matchedMods);

            for (String name : names) {
                VecInt vector = new VecInt();
                matchedMods.stream().filter(mod -> mod.getName().equals(name)).forEach(mod -> {
                    int literal = matchedMods.indexOf(mod) + 1;
                    if (!vector.contains(literal)) {
                        vector.push(literal);
                    }
                });
                try {
                    solver.addExactly(vector, 1);
                } catch (ContradictionException e) {
                    System.out.println(vector);
                    e.printStackTrace();
                    return null;
                }
            }

            if (solver.isSatisfiable()) {
                System.out.println(getName() + " Found solution");
                Set<Mod> solution = new HashSet<>(confirmedMods);
                for (int v : solver.model()) {
                    if (v > 0) {
                        Mod mod = matchedMods.get(v - 1);
                        solution.add(mod);
                        System.out.println(" - " + mod);
                    }
                }
                Set<Mod> result = resolveDependencies(recursionInterval, solution);
                if (result != null) {
                    return result;
                } else {
                    // try another solution
                    VecInt block = new VecInt(solver.model());
                    solver.reset();
                    solver.addBlockingClause(block);
                }
            } else {
                System.out.println("Didn't find a solution for " + getName());
                return null;
            }
        }
        System.out.println(getName() + " didn't find solution");
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Modpack modpack = (Modpack) o;

        if (!name.equals(modpack.name)) return false;
        return path.equals(modpack.path);

    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + path.hashCode();
        return result;
    }
}

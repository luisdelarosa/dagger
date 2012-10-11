/*
 * Copyright (C) 2012 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dagger.internal;

import dagger.internal.codegen.DotWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Emits an object graph in dot format.
 */
public final class GraphVisualizer {
  private static final Pattern KEY_PATTERN = Pattern.compile(""
      + "(?:@"            // Full annotation start.
      + "(?:[\\w$]+\\.)*" // Annotation package
      + "([\\w$]+)"       // Annotation simple name. Group 1.
      + "(?:\\(.*\\))?"   // Annotation arguments
      + "/)?"             // Full annotation end.
      + "(?:members/)?"   // Members prefix.
      + "(?:[\\w$]+\\.)*" // Type package.
      + "([\\w$]+)"       // Type simple name. Group 2.
      + "(\\<[^/]+\\>)?"  // Type parameters. Group 3.
      + "((\\[\\])*)"     // Arrays. Group 4.
      + "");

  private final DotWriter writer;
  private Map<Binding<?>, String> bindingToShortName;
  private final Set<Binding<?>> writtenNodes = new LinkedHashSet<Binding<?>>();

  public GraphVisualizer(DotWriter writer) {
    this.writer = writer;
  }

  public void write(Map<String, Binding<?>> bindings) throws IOException {
    buildNamesIndex(bindings);

    writer.beginGraph("concentrate", "true");
    writer.nodeDefaults("shape", "box");
    for (Map.Entry<Binding<?>, String> entry : bindingToShortName.entrySet()) {
      writeBinding(entry);
    }
    writer.endGraph();
  }

  private void writeBinding(Map.Entry<Binding<?>, String> entry) throws IOException {
    Binding<?> source = entry.getKey();
    Set<Binding<?>> dependencies = new HashSet<Binding<?>>();
    source.getDependencies(dependencies, dependencies);

    for (Binding<?> targetBinding : dependencies) {
      writeEdge(writer, bindingToShortName, source, targetBinding);
    }
  }

  private void writeEdge(DotWriter writer, Map<Binding<?>, String> namesIndex,
      Binding<?> source, Binding<?> target) throws IOException {
    List<String> attributes = new ArrayList<String>();

    if (target instanceof BuiltInBinding) {
      target = ((BuiltInBinding<?>) target).getDelegate();
      attributes.add("style");
      attributes.add("dashed");
    } else if (target instanceof LazyBinding) {
      target = ((LazyBinding<?>) target).getDelegate();
      attributes.add("style");
      attributes.add("dashed");
    }

    String sourceName = namesIndex.get(source);
    String targetName = namesIndex.get(target);

    writeNode(sourceName, source);
    writeNode(targetName, target);

    writer.edge(sourceName, targetName, attributes.toArray(new String[attributes.size()]));
  }

  private void writeNode(String name, Binding<?> node) throws IOException {
    if (!writtenNodes.add(node)) return;

    List<String> attributes = new ArrayList<String>();

    if (node.getType() == Binding.AT_INJECT) {
      attributes.add("color");
      attributes.add("royalblue3");
    } else if (node.getType() == Binding.PROVIDER_METHOD) {
      attributes.add("color");
      attributes.add("darkgreen");
    }

    if (node.isSingleton()) {
      attributes.add("style");
      attributes.add("setlinewidth(3)");
    }

    if (!attributes.isEmpty()) {
      writer.node(name, attributes.toArray(new String[attributes.size()]));
    }
  }

  private Map<Binding<?>, String> buildNamesIndex(Map<String, Binding<?>> bindings) {
    if (bindingToShortName != null) throw new IllegalStateException();

    // Optimistically shorten each binding to the class short name; remembering collisions.
    Map<String, Binding<?>> shortNameToBinding = new TreeMap<String, Binding<?>>();
    Set<Binding<?>> collisions = new HashSet<Binding<?>>();
    for (Map.Entry<String, Binding<?>> entry : bindings.entrySet()) {
      String key = entry.getKey();
      Binding<?> binding = entry.getValue();
      String shortName = shortName(key);
      Binding<?> collision = shortNameToBinding.put(shortName, binding);
      if (collision != null && collision != binding) {
        collisions.add(binding);
        collisions.add(collision);
      }
    }

    // Replace collisions with full names.
    for (Map.Entry<String, Binding<?>> entry : bindings.entrySet()) {
      Binding<?> binding = entry.getValue();
      if (collisions.contains(binding)) {
        String key = entry.getKey();
        String shortName = shortName(key);
        shortNameToBinding.remove(shortName);
        shortNameToBinding.put(key, binding);
      }
    }

    // Reverse the map.
    bindingToShortName = new LinkedHashMap<Binding<?>, String>();
    for (Map.Entry<String, Binding<?>> entry : shortNameToBinding.entrySet()) {
      bindingToShortName.put(entry.getValue(), entry.getKey());
    }

    return bindingToShortName;
  }

  String shortName(String key) {
    Matcher matcher = KEY_PATTERN.matcher(key);
    if (!matcher.matches()) throw new IllegalArgumentException("Unexpected key: " + key);
    StringBuilder result = new StringBuilder();

    String annotationSimpleName = matcher.group(1);
    if (annotationSimpleName != null) {
      result.append('@').append(annotationSimpleName).append(' ');
    }

    String simpleName = matcher.group(2);
    result.append(simpleName);

    String typeParameters = matcher.group(3);
    if (typeParameters != null) {
      result.append(typeParameters);
    }

    String arrays = matcher.group(4);
    if (arrays != null) {
      result.append(arrays);
    }

    return result.toString();
  }
}

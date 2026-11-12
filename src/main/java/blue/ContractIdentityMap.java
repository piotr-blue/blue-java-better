package blue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Identity trie with constant-time snapshots. A map edits its own branches in place; a fork revokes
 * that ownership so shared branches are copied before either map changes them.
 */
final class ContractIdentityMap<V> {
  private Trie<V> root;
  private Object owner = new Object();
  private int size;

  ContractIdentityMap<V> fork() {
    var next = new ContractIdentityMap<V>();
    next.root = root;
    next.size = size;
    owner = new Object();
    return next;
  }

  private Leaf<V> leaf(Object key) {
    return root == null ? null : root.find(key, System.identityHashCode(key), 0);
  }

  V get(Object key) {
    var leaf = leaf(key);
    return leaf == null ? null : leaf.value;
  }

  boolean containsKey(Object key) {
    return leaf(key) != null;
  }

  void put(Object key, V value) {
    var before = leaf(key);
    if (before != null && Objects.equals(before.value, value)) {
      return;
    }
    var added = new Leaf<V>(key, value, System.identityHashCode(key));
    root = root == null ? added : root.put(added, 0, owner);
    if (before == null) {
      size++;
    }
  }

  void remove(Object key) {
    var before = leaf(key);
    if (before == null) {
      return;
    }
    root = root.remove(key, before.hash, 0, owner);
    size--;
  }

  int trim(int maximum) {
    if (maximum < 0) {
      throw new IllegalArgumentException("Negative identity-map capacity");
    }
    int before = size;
    if (size <= maximum) {
      return 0;
    }
    var pending = new ArrayDeque<Trie<V>>();
    // Traverse a frozen root while removals edit the current one.
    owner = new Object();
    pending.push(root);
    while (size > maximum) {
      var node = pending.pop();
      if (node instanceof Leaf<V> leaf) {
        remove(leaf.key);
      } else if (node instanceof Collision<V> collision) {
        pending.addAll(collision.leaves);
      } else {
        Collections.addAll(pending, ((Branch<V>) node).children);
      }
    }
    return before - size;
  }

  int size() {
    return size;
  }

  private sealed interface Trie<V> permits Leaf, Collision, Branch {
    Leaf<V> find(Object key, int hash, int shift);

    Trie<V> put(Leaf<V> value, int shift, Object owner);

    Trie<V> remove(Object key, int hash, int shift, Object owner);
  }

  private record Leaf<V>(Object key, V value, int hash) implements Trie<V> {
    public Leaf<V> find(Object k, int h, int shift) {
      return key == k ? this : null;
    }

    public Trie<V> put(Leaf<V> added, int shift, Object owner) {
      if (key == added.key) {
        return added;
      }
      if (hash == added.hash) {
        return new Collision<>(hash, List.of(this, added));
      }
      return merge(this, hash, added, added.hash, shift, owner);
    }

    public Trie<V> remove(Object k, int h, int shift, Object owner) {
      return key == k ? null : this;
    }
  }

  private record Collision<V>(int hash, List<Leaf<V>> leaves) implements Trie<V> {
    public Leaf<V> find(Object key, int h, int shift) {
      if (hash != h) {
        return null;
      }
      for (var leaf : leaves) {
        if (leaf.key == key) {
          return leaf;
        }
      }
      return null;
    }

    public Trie<V> put(Leaf<V> added, int shift, Object owner) {
      if (hash != added.hash) {
        return merge(this, hash, added, added.hash, shift, owner);
      }
      var next = new ArrayList<>(leaves);
      for (int i = 0; i < next.size(); i++) {
        if (next.get(i).key == added.key) {
          next.set(i, added);
          return new Collision<>(hash, List.copyOf(next));
        }
      }
      next.add(added);
      return new Collision<>(hash, List.copyOf(next));
    }

    public Trie<V> remove(Object key, int h, int shift, Object owner) {
      if (hash != h) {
        return this;
      }
      var next = leaves.stream().filter(leaf -> leaf.key != key).toList();
      if (next.size() == leaves.size()) {
        return this;
      }
      return next.size() == 1 ? next.getFirst() : new Collision<>(hash, next);
    }
  }

  private static final class Branch<V> implements Trie<V> {
    private final Object owner;
    private int bitmap;
    private Trie<V>[] children;

    Branch(Object owner, int bitmap, Trie<V>[] children) {
      this.owner = owner;
      this.bitmap = bitmap;
      this.children = children;
    }

    private int offset(int bit) {
      return Integer.bitCount(bitmap & (bit - 1));
    }

    private Branch<V> editable(Object editor) {
      return owner == editor ? this : new Branch<>(editor, bitmap, children.clone());
    }

    public Leaf<V> find(Object key, int hash, int shift) {
      int bit = 1 << ((hash >>> shift) & 31);
      return (bitmap & bit) == 0 ? null : children[offset(bit)].find(key, hash, shift + 5);
    }

    public Trie<V> put(Leaf<V> added, int shift, Object editor) {
      int bit = 1 << ((added.hash >>> shift) & 31), index = offset(bit);
      Branch<V> next;
      if ((bitmap & bit) == 0) {
        Trie<V>[] expanded = Arrays.copyOf(children, children.length + 1);
        System.arraycopy(children, index, expanded, index + 1, children.length - index);
        expanded[index] = added;
        if (owner != editor) {
          return new Branch<>(editor, bitmap | bit, expanded);
        }
        children = expanded;
        bitmap |= bit;
        next = this;
      } else {
        next = editable(editor);
        next.children[index] = next.children[index].put(added, shift + 5, editor);
      }
      return next;
    }

    public Trie<V> remove(Object key, int hash, int shift, Object editor) {
      int bit = 1 << ((hash >>> shift) & 31);
      if ((bitmap & bit) == 0) {
        return this;
      }
      int index = offset(bit);
      Trie<V> changed = children[index].remove(key, hash, shift + 5, editor);
      if (changed == children[index]) {
        return this;
      }
      if (changed == null) {
        if (children.length == 1) {
          return null;
        }
        Trie<V>[] reduced = Arrays.copyOf(children, children.length - 1);
        System.arraycopy(children, index + 1, reduced, index, children.length - index - 1);
        if (reduced.length == 1 && !(reduced[0] instanceof Branch<?>)) {
          return reduced[0];
        }
        if (owner != editor) {
          return new Branch<>(editor, bitmap & ~bit, reduced);
        }
        children = reduced;
        bitmap &= ~bit;
        return this;
      }
      Branch<V> next = editable(editor);
      next.children[index] = changed;
      if (next.children.length == 1 && !(changed instanceof Branch<?>)) {
        return changed;
      }
      return next;
    }
  }

  @SafeVarargs
  @SuppressWarnings("varargs") // The array becomes the branch's own children and stays Trie<V>[].
  private static <V> Branch<V> branch(Object owner, int bitmap, Trie<V>... children) {
    return new Branch<>(owner, bitmap, children);
  }

  private static <V> Trie<V> merge(Trie<V> a, int ah, Trie<V> b, int bh, int shift, Object owner) {
    int x = (ah >>> shift) & 31, y = (bh >>> shift) & 31;
    if (x == y) {
      return branch(owner, 1 << x, merge(a, ah, b, bh, shift + 5, owner));
    }
    return x < y
        ? branch(owner, (1 << x) | (1 << y), a, b)
        : branch(owner, (1 << x) | (1 << y), b, a);
  }
}

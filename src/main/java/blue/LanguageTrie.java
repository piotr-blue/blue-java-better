package blue;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/** Immutable hash trie whose union skips physically shared branches. */
final class LanguageTrie<K, V> extends AbstractMap<K, V> {
  private final Tree<K, V> root;

  private LanguageTrie(Tree<K, V> root) {
    this.root = root;
  }

  static <K, V> LanguageTrie<K, V> empty() {
    return new LanguageTrie<>(null);
  }

  LanguageTrie<K, V> with(K key, V value) {
    Tree<K, V> next = union(root, new Leaf<>(key, value, hash(key)), 0);
    return next == root ? this : new LanguageTrie<>(next);
  }

  LanguageTrie<K, V> without(Object key) {
    Tree<K, V> next = remove(root, key, hash(key), 0);
    return next == root ? this : new LanguageTrie<>(next);
  }

  LanguageTrie<K, V> union(LanguageTrie<K, V> source) {
    Tree<K, V> next = union(root, source.root, 0);
    return next == root ? this : next == source.root ? source : new LanguageTrie<>(next);
  }

  @Override
  public int size() {
    return size(root);
  }

  @Override
  public V get(Object key) {
    Leaf<K, V> found = find(root, key, hash(key), 0);
    return found == null ? null : found.value;
  }

  @Override
  public boolean containsKey(Object key) {
    return find(root, key, hash(key), 0) != null;
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    return new AbstractSet<>() {
      public int size() {
        return LanguageTrie.this.size();
      }

      public Iterator<Entry<K, V>> iterator() {
        return new Iterator<>() {
          final ArrayDeque<Tree<K, V>> pending = new ArrayDeque<>();

          {
            if (root != null) pending.add(root);
          }

          public boolean hasNext() {
            return !pending.isEmpty();
          }

          public Entry<K, V> next() {
            while (!pending.isEmpty()) {
              Tree<K, V> next = pending.removeLast();
              if (next instanceof Leaf<K, V> leaf)
                return new SimpleImmutableEntry<>(leaf.key, leaf.value);
              if (next instanceof Collision<K, V> collision) pending.addAll(collision.leaves);
              else Collections.addAll(pending, ((Branch<K, V>) next).children);
            }
            throw new NoSuchElementException();
          }
        };
      }
    };
  }

  private sealed interface Tree<K, V> permits Leaf, Collision, Branch {
    int size();
  }

  private record Leaf<K, V>(K key, V value, int hash) implements Tree<K, V> {
    public int size() {
      return 1;
    }
  }

  private record Collision<K, V>(int hash, List<Leaf<K, V>> leaves) implements Tree<K, V> {
    public int size() {
      return leaves.size();
    }
  }

  private record Branch<K, V>(int bitmap, Tree<K, V>[] children, int size) implements Tree<K, V> {
    int offset(int bit) {
      return Integer.bitCount(bitmap & (bit - 1));
    }

    Tree<K, V> child(int bit) {
      return (bitmap & bit) == 0 ? null : children[offset(bit)];
    }
  }

  private static int hash(Object key) {
    int hash = Objects.hashCode(key);
    return hash ^ (hash >>> 16);
  }

  private static int size(Tree<?, ?> tree) {
    return tree == null ? 0 : tree.size();
  }

  private static int hash(Tree<?, ?> tree) {
    return tree instanceof Leaf<?, ?> leaf ? leaf.hash : ((Collision<?, ?>) tree).hash;
  }

  private static int bit(int hash, int shift) {
    return 1 << ((hash >>> shift) & 31);
  }

  private static <K, V> Leaf<K, V> find(Tree<K, V> tree, Object key, int hash, int shift) {
    if (tree == null) return null;
    if (tree instanceof Leaf<K, V> leaf) return Objects.equals(key, leaf.key) ? leaf : null;
    if (tree instanceof Collision<K, V> collision) {
      if (hash != collision.hash) return null;
      for (Leaf<K, V> leaf : collision.leaves) if (Objects.equals(key, leaf.key)) return leaf;
      return null;
    }
    Branch<K, V> branch = (Branch<K, V>) tree;
    return find(branch.child(bit(hash, shift)), key, hash, shift + 5);
  }

  /** Right-hand values win, including distinct objects which happen to compare equal. */
  private static <K, V> Tree<K, V> union(Tree<K, V> left, Tree<K, V> right, int shift) {
    if (left == right || right == null) return left;
    if (left == null) return right;
    if (left instanceof Branch<K, V> a && right instanceof Branch<K, V> b) {
      int bitmap = a.bitmap | b.bitmap;
      Tree<K, V>[] children = array(Integer.bitCount(bitmap));
      boolean sameLeft = bitmap == a.bitmap, sameRight = bitmap == b.bitmap;
      int index = 0, size = 0;
      for (int bits = bitmap; bits != 0; bits &= bits - 1) {
        int bit = Integer.lowestOneBit(bits);
        Tree<K, V> x = a.child(bit), y = b.child(bit);
        Tree<K, V> merged = union(x, y, shift + 5);
        children[index++] = merged;
        size += merged.size();
        sameLeft &= merged == x;
        sameRight &= merged == y;
      }
      return sameLeft ? a : sameRight ? b : new Branch<>(bitmap, children, size);
    }
    if (left instanceof Branch<K, V> branch) {
      int bit = bit(hash(right), shift);
      return replace(branch, bit, union(branch.child(bit), right, shift + 5));
    }
    if (right instanceof Branch<K, V> branch) {
      int bit = bit(hash(left), shift);
      return replace(branch, bit, union(left, branch.child(bit), shift + 5));
    }
    int a = hash(left), b = hash(right);
    if (a != b) {
      int x = bit(a, shift), y = bit(b, shift);
      if (x == y) return branch(x, union(left, right, shift + 5));
      return Integer.compareUnsigned(x, y) < 0
          ? branch(x | y, left, right)
          : branch(x | y, right, left);
    }
    if (right instanceof Leaf<K, V> leaf) return insertCollision(left, leaf);
    Tree<K, V> result = left;
    for (Leaf<K, V> leaf : ((Collision<K, V>) right).leaves) result = insertCollision(result, leaf);
    return result;
  }

  private static <K, V> Tree<K, V> insertCollision(Tree<K, V> tree, Leaf<K, V> added) {
    if (tree instanceof Leaf<K, V> leaf) {
      if (Objects.equals(leaf.key, added.key)) return leaf.value == added.value ? leaf : added;
      return new Collision<>(leaf.hash, List.of(leaf, added));
    }
    Collision<K, V> collision = (Collision<K, V>) tree;
    for (int i = 0; i < collision.leaves.size(); i++) {
      Leaf<K, V> leaf = collision.leaves.get(i);
      if (Objects.equals(leaf.key, added.key)) {
        if (leaf.value == added.value) return tree;
        List<Leaf<K, V>> next = new ArrayList<>(collision.leaves);
        next.set(i, added);
        return new Collision<>(collision.hash, List.copyOf(next));
      }
    }
    List<Leaf<K, V>> next = new ArrayList<>(collision.leaves);
    next.add(added);
    return new Collision<>(collision.hash, List.copyOf(next));
  }

  private static <K, V> Tree<K, V> remove(Tree<K, V> tree, Object key, int hash, int shift) {
    if (tree == null) return null;
    if (tree instanceof Leaf<K, V> leaf) return Objects.equals(key, leaf.key) ? null : tree;
    if (tree instanceof Collision<K, V> collision) {
      if (hash != collision.hash) return tree;
      for (int i = 0; i < collision.leaves.size(); i++) {
        if (Objects.equals(key, collision.leaves.get(i).key)) {
          List<Leaf<K, V>> next = new ArrayList<>(collision.leaves);
          next.remove(i);
          return next.size() == 1 ? next.getFirst() : new Collision<>(hash, List.copyOf(next));
        }
      }
      return tree;
    }
    Branch<K, V> branch = (Branch<K, V>) tree;
    int bit = bit(hash, shift);
    return replace(branch, bit, remove(branch.child(bit), key, hash, shift + 5));
  }

  private static <K, V> Tree<K, V> replace(Branch<K, V> branch, int bit, Tree<K, V> child) {
    Tree<K, V> previous = branch.child(bit);
    if (previous == child) return branch;
    int index = branch.offset(bit);
    Tree<K, V>[] children;
    int bitmap = branch.bitmap;
    if (previous == null) {
      children = Arrays.copyOf(branch.children, branch.children.length + 1);
      System.arraycopy(branch.children, index, children, index + 1, branch.children.length - index);
      children[index] = child;
      bitmap |= bit;
    } else if (child == null) {
      children = Arrays.copyOf(branch.children, branch.children.length - 1);
      System.arraycopy(branch.children, index + 1, children, index, children.length - index);
      bitmap &= ~bit;
    } else {
      children = branch.children.clone();
      children[index] = child;
    }
    if (children.length == 0) return null;
    if (children.length == 1 && !(children[0] instanceof Branch<?, ?>)) return children[0];
    return new Branch<>(bitmap, children, branch.size - size(previous) + size(child));
  }

  @SuppressWarnings("unchecked")
  private static <K, V> Tree<K, V>[] array(int size) {
    return (Tree<K, V>[]) new Tree<?, ?>[size];
  }

  @SafeVarargs
  @SuppressWarnings("varargs")
  private static <K, V> Tree<K, V> branch(int bitmap, Tree<K, V>... children) {
    int size = 0;
    for (Tree<K, V> child : children) size += child.size();
    return new Branch<>(bitmap, children, size);
  }
}

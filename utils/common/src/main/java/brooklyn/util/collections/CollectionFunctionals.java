package brooklyn.util.collections;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

/** things which it seems should be in guava, but i can't find 
 * @author alex */
public class CollectionFunctionals {

    public static Supplier<Integer> sizeSupplier(final Iterable<?> collection) {
        return new Supplier<Integer>() {
            @Override
            public Integer get() {
                return Iterables.size(collection);
            }
        };
    }
    
    public static Function<Iterable<?>, Integer> sizeFunction() {
        return new Function<Iterable<?>, Integer>() {
            @Override
            public Integer apply(Iterable<?> input) {
                return Iterables.size(input);
            }
        };
    }

    public static <K> Function<Map<K,?>,Set<K>> keys() {
        return new Function<Map<K,?>, Set<K>>() {
            @Override
            public Set<K> apply(Map<K, ?> input) {
                return input.keySet();
            }
        };
    }

    public static <K> Function<Map<K, ?>, Integer> mapSize() {
        return Functions.compose(CollectionFunctionals.sizeFunction(), CollectionFunctionals.<K>keys());
    }

    /** default guava Equals predicate will reflect order of target, and will fail when matching against a list;
     * this treats them both as sets */
    public static Predicate<Iterable<?>> equalsSetOf(Object... target) {
        return equalsSet(Arrays.asList(target));
    }
    public static Predicate<Iterable<?>> equalsSet(final Iterable<?> target) {
        return new Predicate<Iterable<?>>() {
            @Override
            public boolean apply(@Nullable Iterable<?> input) {
                if (input==null) return false;
                return Sets.newHashSet(target).equals(Sets.newHashSet(input));
            }
        };
    }

    public static Predicate<Iterable<?>> sizeEquals(int targetSize) {
        return Predicates.compose(Predicates.equalTo(targetSize), CollectionFunctionals.sizeFunction());
    }

    public static <K> Predicate<Map<K,?>> mapSizeEquals(int targetSize) {
        return Predicates.compose(Predicates.equalTo(targetSize), CollectionFunctionals.<K>mapSize());
    }

}

package fr.inria.diversify.dspot.dynamic.logger;

import java.util.*;

/**
 * User: Simon
 * Date: 23/03/16
 * Time: 15:48
 */
public class TypeUtils {
    protected static Set<Class<?>> WRAPPER_TYPES = getWrapperTypes();

    public static boolean isPrimitiveCollectionOrMap(Object collectionOrMap) {
        if(Collection.class.isInstance(collectionOrMap)) {
            Collection collection = (Collection) collectionOrMap;
            if(collection.isEmpty()) {
                return true;
            } else {
                Iterator iterator = collection.iterator();
                while (iterator.hasNext()) {
                    Object next = iterator.next();
                    if(next != null) {
                        return isPrimitive(next);
                    }
                }
                return true;
            }
        } else {
            Map map = (Map) collectionOrMap;
            if(map.isEmpty()) {
                return true;
            } else {
                boolean isKeyPrimitive = false;
                boolean isValuePrimitive = false;
                Iterator keyIterator = map.keySet().iterator();
                while (keyIterator.hasNext()) {
                    Object next = keyIterator.next();
                    if(next != null && isPrimitive(next)) {
                        isKeyPrimitive = true;
                        break;
                    }
                }
                if(isKeyPrimitive) {
                    Iterator valueIterator = map.keySet().iterator();
                    while (valueIterator.hasNext()) {
                        Object next = valueIterator.next();
                        if (next != null && isPrimitive(next)) {
                            isValuePrimitive = true;
                            break;
                        }
                    }
                }
                return isKeyPrimitive && isValuePrimitive;
            }
        }
    }

    public static boolean isPrimitive(Object object) {
        return object.getClass().isPrimitive()
                || isWrapperType(object)
                || String.class.isInstance(object);
    }

    public static boolean isWrapperType(Object o) {
        return WRAPPER_TYPES.contains(o.getClass());
    }

    protected static Set<Class<?>> getWrapperTypes() {
        Set<Class<?>> ret = new HashSet<Class<?>>();
        ret.add(Boolean.class);
        ret.add(Character.class);
        ret.add(Byte.class);
        ret.add(Short.class);
        ret.add(Integer.class);
        ret.add(Long.class);
        ret.add(Float.class);
        ret.add(Double.class);
        ret.add(Void.class);
        return ret;
    }

}
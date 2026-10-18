/*
 * Phonalyser - precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.test;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodUtils;

/**
 * Base of the tests that reach private members of the class under test - the
 * members a productive class keeps private and does not widen to let a test
 * in.  Members are named by string, which is what reflection takes; a member
 * that cannot be reached fails the test with its name, so a renamed member
 * shows where it is used and not as a bare reflection error.
 */
public abstract class AbstractReflectiveTest {

    /**
     * Calls the method {@code method} of {@code target}, whatever its access,
     * with {@code args}.
     *
     * @return the method's result as the type the caller takes it as,
     *         {@code null} for a void method
     */
    @SuppressWarnings("unchecked")
    protected <T> T call(Object target, String method, Object... args) {
        try {
            return (T) MethodUtils.invokeMethod(target, true, method, args);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("the method " + method + " of "
                    + target.getClass().getSimpleName() + " is not reachable", ex);
        }
    }

    /** The value of the field {@code name} of {@code target}, whatever its
     *  access, as the type the caller takes it as. */
    @SuppressWarnings("unchecked")
    protected <T> T field(Object target, String name) {
        try {
            return (T) FieldUtils.readField(target, name, true);
        } catch (IllegalAccessException ex) {
            throw new AssertionError("the field " + name + " of "
                    + target.getClass().getSimpleName() + " is not reachable", ex);
        }
    }
}

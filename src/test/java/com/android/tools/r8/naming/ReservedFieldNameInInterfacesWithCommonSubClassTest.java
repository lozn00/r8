// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.android.tools.r8.NeverMerge;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.FieldSubject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Tests that the fields of two interfaces I and J are given distinct names, when the interfaces
 * have a common sub class, which does not implement both interfaces directly.
 */
@RunWith(Parameterized.class)
public class ReservedFieldNameInInterfacesWithCommonSubClassTest extends TestBase {

  private final boolean reserveName;

  @Parameterized.Parameters(name = "Reserve name: {0}")
  public static Boolean[] data() {
    return BooleanUtils.values();
  }

  public ReservedFieldNameInInterfacesWithCommonSubClassTest(boolean reserveName) {
    this.reserveName = reserveName;
  }

  @Test
  public void test() throws Exception {
    String expectedOutput = StringUtils.lines("Hello world!");
    CodeInspector inspector =
        testForR8(Backend.DEX)
            .addProgramClasses(TestClass.class, A.class, B.class, I.class, J.class)
            .enableMergeAnnotations()
            .addKeepMainRule(TestClass.class)
            .addKeepRules(
                reserveName
                    ? "-keepclassmembernames class "
                        + J.class.getTypeName()
                        + "{ java.lang.String a; }"
                    : "")
            .run(TestClass.class)
            .assertSuccessWithOutput(expectedOutput)
            .inspector();

    ClassSubject iClassSubject = inspector.clazz(I.class);
    assertThat(iClassSubject, isPresent());

    ClassSubject jClassSubject = inspector.clazz(J.class);
    assertThat(jClassSubject, isPresent());

    ClassSubject aClassSubject = inspector.clazz(A.class);
    assertThat(aClassSubject, isPresent());

    ClassSubject bClassSubject = inspector.clazz(B.class);
    assertThat(bClassSubject, isPresent());

    FieldSubject f1FieldSubject = iClassSubject.uniqueFieldWithName("f1");
    assertThat(f1FieldSubject, isPresent());

    FieldSubject aFieldSubject = jClassSubject.uniqueFieldWithName("a");
    assertThat(aFieldSubject, isPresent());

    if (reserveName) {
      assertEquals("a", f1FieldSubject.getFinalName());
      // TODO(b/128973195): J.a should not be renamed to the same as I.f1.
      assertEquals("a", aFieldSubject.getFinalName());
    } else {
      // TODO(b/128973195): I.f1 Should not be renamed to the same as J.a.
      assertEquals("a", f1FieldSubject.getFinalName());
      assertEquals("a", aFieldSubject.getFinalName());
    }
  }

  @NeverMerge
  interface I {

    String f1 = System.currentTimeMillis() >= 0 ? "Hello " : null;
  }

  @NeverMerge
  interface J {

    String a = System.currentTimeMillis() >= 0 ? "world!" : null;
  }

  @NeverMerge
  static class A implements I {}

  @NeverMerge
  static class B extends A implements J {

    @Override
    public String toString() {
      return f1 + a;
    }
  }

  static class TestClass {

    public static void main(String[] args) {
      System.out.println(new B());
    }
  }
}

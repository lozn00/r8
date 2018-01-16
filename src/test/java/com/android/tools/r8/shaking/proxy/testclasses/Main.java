// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.shaking.proxy.testclasses;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class Main {

  private void x(BaseInterface iface) {
    iface.method();
    if (iface != null) {
      // The next two invokes are inline candidates, as the receiver is known to be non-null.
      iface.method();
      iface.method();
    }
  }

  private void y(SubInterface iface) {
    iface.method();
    if (iface != null) {
      // The next two invokes are inline candidates, as the receiver is known to be non-null.
      iface.method();
      iface.method();
    }
  }

  private void run(String[] args) {
    x(new TestClass());
    x(createProxyOfInterface(BaseInterface.class));
    y(new TestClass());
    y(createProxyOfInterface(SubInterface.class));
  }

  public static void main(String[] args) {
    try {
      new Main().run(args);
    } catch (Throwable t) {
      System.out.println("EXCEPTION");
      return;
    }
    System.out.println("SUCCESS");
  }

  @SuppressWarnings("unchecked")
  private static <T> T createProxyOfInterface(Class<T> iface) {
    return (T) Proxy.newProxyInstance(
        iface.getClassLoader(), new Class[]{iface},
        (Object o, Method method, Object[] objects) -> {
          System.out.println("Proxy");
          return null;
        });
  }
}

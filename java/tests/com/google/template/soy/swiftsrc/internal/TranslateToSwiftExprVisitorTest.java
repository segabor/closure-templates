package com.google.template.soy.swiftsrc.internal;

import static com.google.template.soy.swiftsrc.internal.SoyExprForSwiftSubject.assertThatSoyExpr;

import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.swiftsrc.restricted.SwiftExpr;
import com.google.template.soy.swiftsrc.restricted.SwiftListExpr;
import com.google.template.soy.swiftsrc.restricted.SwiftStringExpr;

@RunWith(JUnit4.class)
public class TranslateToSwiftExprVisitorTest {

  @Test
  public void testNullLiteral() {
    assertThatSoyExpr("null").translatesTo("nil", Integer.MAX_VALUE);
  }

  @Test
  public void testBooleanLiteral() {
    assertThatSoyExpr("true").translatesTo("true", Integer.MAX_VALUE);
    assertThatSoyExpr("false").translatesTo("false", Integer.MAX_VALUE);
  }

  @Test
  public void testStringLiteral() {
    assertThatSoyExpr("'waldo'")
        .translatesTo(new SwiftExpr("\"waldo\"", Integer.MAX_VALUE), SwiftStringExpr.class);
  }

  @Test
  public void testListLiteral() {
    assertThatSoyExpr("[]").translatesTo(new SwiftExpr("[Any?]()", Integer.MAX_VALUE), SwiftListExpr.class);
    assertThatSoyExpr("['blah', 123, $foo]")
        .translatesTo(
            new SwiftExpr("[\"blah\", 123, data[\"foo\"]] as [Any?]", Integer.MAX_VALUE), SwiftListExpr.class);
  }

  @Test
  public void testMapLiteral() {
    // Unquoted keys.
    assertThatSoyExpr("[:]").translatesTo("[String:Any?]()", Integer.MAX_VALUE);
    assertThatSoyExpr("['aaa': 123, 'bbb': 'blah']")
        .translatesTo("[\"aaa\": 123, \"bbb\": \"blah\"] as [String:Any?]", Integer.MAX_VALUE);
    assertThatSoyExpr("['aaa': $foo, 'bbb': 'blah']")
        .translatesTo(
            "[\"aaa\": data[\"foo\"], \"bbb\": \"blah\"] as [String:Any?]", Integer.MAX_VALUE);
  }

  @Test
  public void testGlobals() {
    ImmutableMap<String, Object> globals =
        ImmutableMap.<String, Object>builder()
            .put("STR", "Hello World")
            .put("NUM", 55)
            .put("BOOL", true)
            .build();

    assertThatSoyExpr("STR").withGlobals(globals).translatesTo("'Hello World'", Integer.MAX_VALUE);
    assertThatSoyExpr("NUM").withGlobals(globals).translatesTo("55", Integer.MAX_VALUE);
    assertThatSoyExpr("BOOL").withGlobals(globals).translatesTo("true", Integer.MAX_VALUE);
  }

  @Test
  public void testDataRef() {
    assertThatSoyExpr("$boo").translatesTo("data.get(\"boo\")", Integer.MAX_VALUE);
    assertThatSoyExpr("$boo.goo").translatesTo("data.get(\"boo\").get(\"goo\")", Integer.MAX_VALUE);
    assertThatSoyExpr("$boo['goo']")
        .translatesTo("runtime.key_safe_data_access(data.get(\"boo\"), \"goo\")", Integer.MAX_VALUE);
    assertThatSoyExpr("$boo[0]")
        .translatesTo("runtime.key_safe_data_access(data.get(\"boo\"), 0)", Integer.MAX_VALUE);
    assertThatSoyExpr("$boo[0]")
        .translatesTo("runtime.key_safe_data_access(data.get(\"boo\"), 0)", Integer.MAX_VALUE);
    assertThatSoyExpr("$boo[$foo][$foo+1]")
        .translatesTo(
            "runtime.key_safe_data_access("
                + "runtime.key_safe_data_access(data.get('boo'), data.get('foo')), "
                + "runtime.type_safe_add(data.get('foo'), 1))",
            Integer.MAX_VALUE);

    assertThatSoyExpr("$boo?.goo")
        .translatesTo(
            "None if data.get('boo') is None else data.get('boo').get('goo')",
            Operator.CONDITIONAL);
    assertThatSoyExpr("$boo?[0]?[1]")
        .translatesTo(
            "None if data.get('boo') is None else "
                + "None if runtime.key_safe_data_access(data.get('boo'), 0) is None else "
                + "runtime.key_safe_data_access("
                + "runtime.key_safe_data_access(data.get('boo'), 0), 1)",
            Operator.CONDITIONAL);
  }

  @Test
  public void testDataRef_localVars() {
    Map<String, SwiftExpr> frame = Maps.newHashMap();
    frame.put("zoo", new SwiftExpr("zooData8", Integer.MAX_VALUE));

    assertThatSoyExpr("$zoo").with(frame).translatesTo("zooData8", Integer.MAX_VALUE);
    assertThatSoyExpr("$zoo.boo")
        .with(frame)
        .translatesTo("zooData8.get('boo')", Integer.MAX_VALUE);
  }

  @Test
  public void testBasicOperators() {
    assertThatSoyExpr("not $boo or true and $foo")
        .translatesTo("data[\"boo\"] == nil or true and data[\"foo\"] != nil", Operator.OR);
  }

  @Test // fixme
  public void testEqualOperator() {
    assertThatSoyExpr("'5' == 5 ? 1 : 0")
        .translatesTo("1 if runtime.type_safe_eq('5', 5) else 0", 1);
    assertThatSoyExpr("'5' == $boo ? 1 : 0")
        .translatesTo("1 if runtime.type_safe_eq('5', data.get('boo')) else 0", 1);
  }

  @Test // fixme
  public void testNotEqualOperator() {
    assertThatSoyExpr("'5' != 5").translatesTo("not runtime.type_safe_eq('5', 5)", Operator.NOT);
  }

  @Test // fixme
  public void testPlusOperator() {
    assertThatSoyExpr("( (8-4) + (2-1) )")
        .translatesTo("runtime.type_safe_add(8 - 4, 2 - 1)", Integer.MAX_VALUE);
  }

  @Test
  public void testNullCoalescingOperator() {
    assertThatSoyExpr("$boo ?: 5")
        .translatesTo(
            "data[\"boo\"] ?? 5", Operator.CONDITIONAL);
  }

  @Test
  public void testConditionalOperator() {
    assertThatSoyExpr("$boo ? 5 : 6")
        .translatesTo("data[\"boo\"] != nil ? 5 : 6", Operator.CONDITIONAL);
  }

  @Test
  public void testCheckNotNull() {
    assertThatSoyExpr("checkNotNull($boo) ? 1 : 0")
        .translatesTo("data[\"boo\"] != nil ? 1 : 0", Operator.CONDITIONAL);
  }

  @Test
  public void testCss() {
    assertThatSoyExpr("css('foo')").translatesTo("getCSSName(\"foo\")", Integer.MAX_VALUE);
    assertThatSoyExpr("css($foo, 'base')")
        .translatesTo("getCSSName(data[\"foo\"], \"base\")", Integer.MAX_VALUE);
  }

  @Test
  public void testXid() {
    assertThatSoyExpr("xid('foo')").translatesTo("getXIDName(\"foo\")", Integer.MAX_VALUE);
  }
}

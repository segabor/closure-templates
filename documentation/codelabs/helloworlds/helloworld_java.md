# Hello World Using Java

## Hello World

Follow these steps to create a simple Hello World template and use it in Java:

1.  Create a new [Maven](https://maven.apache.org) project using your favorite
    IDE. (Soy is a plain Java library and will work with any Java build tool.
    This example uses Maven.) The final directory structure will look like this:

    ```
    .
    ├── pom.xml
    └── src
        └── main
            ├── java
            │   └── example
            │       └── HelloWorld.java
            └── resources
                └── example
                    └── simple.soy
    ```

2.  All files that contain Soy end with the `.soy` file extension and are called
    Soy files. Create `src/main/resources/example/simple.soy` containing the
    following line:

        {namespace examples.simple}

    This line declares a namespace for all the templates that you define in this
    file.

3.  Copy the following template to `src/main/resources/example/simple.soy`,
    making sure that it appears after the namespace declaration:

        {template .helloWorld}
          Hello world!
        {/template}

    This template simply outputs the text `Hello world!`. It has the partial
    name `.helloWorld`, which, when combined with the namespace, forms the fully
    qualified template name `examples.simple.helloWorld`.

4.  Now that we've written the template, we need to write the Java code to
    render the template. To do that, we need to tell Maven to add a dependency
    on Soy.

    `pom.xml` is the main Maven configuration file. Edit to add the dependency:

        <dependencies>
          <dependency>
            <groupId>com.google.template</groupId>
            <artifactId>soy</artifactId>
            <version>2018-03-14 </version> <!-- Or latest version.-->
          </dependency>
        </dependencies>

    (See
    [here](https://maven.apache.org/guides/introduction/introduction-to-the-pom.html)
    for more information about the structure of `pom.xml`.)

5.  Create `src/main/java/example/HelloWorld.java` with the following contents:

        package example;

        import com.google.template.soy.SoyFileSet;
        import com.google.template.soy.tofu.SoyTofu;

        public class HelloWorld {
          public static void main(String[] args) {
            SoyFileSet sfs = SoyFileSet
                .builder()
                .add(HelloWorld.class.getResource("simple.soy"))
                .build();
            SoyTofu tofu = sfs.compileToTofu();
            System.out.println(
                tofu.newRenderer("examples.simple.helloWorld").render());
          }
        }

    This example bundles the template files that you specify (in this case, just
    `simple.soy`) into a `SoyFileSet` object, then compiles the bundle into a
    `SoyTofu` object with a call to `compileToTofu()`. The final line calls the
    template, using the template's fully qualified name
    `examples.simple.helloWorld`, and renders its output to standard out.

6.  Add the following snippet to `pom.xml` to tell Maven how to execute the main
    class (`HelloWorld`):

        <build>
          <plugins>
            <plugin>
              <groupId>org.codehaus.mojo</groupId>
              <artifactId>exec-maven-plugin</artifactId>
              <version>1.6.0</version>
              <executions>
                <execution>
                  <phase>package</phase>
                  <goals>
                    <goal>java</goal>
                  </goals>
                </execution>
              </executions>
              <configuration>
                <mainClass>example.HelloWorld</mainClass>
              </configuration>
            </plugin>
          </plugins>
        </build>

7.  Run `mvn package` at the root of your project. You should see this message
    at standard out:

        Hello world!

## Hello Name and Hello Names

1.  Add the following second template, called `.helloName`, to `simple.soy`.
    Note that `.helloName` takes a required parameter called `name`, which is
    declared by `@param`. It also takes an optional parameter `greetingWord`,
    which is declared by `@param?`. These parameters are referenced in the
    template body using the expressions `$name` and `$greetingWord`,
    respectively. This template also demonstrates that you can conditionally
    include content in templates via the `if-else` commands. You can put this
    template before or after the `.helloWorld` template, just as long as it's
    after the `namespace` declaration.

        /** Greets a person using "Hello" by default. */
        {template .helloName}
          {@param name: string} /** The person's name. */
          {@param? greetingWord: string} /**
                                          * Optional greeting word to use
                                          * instead of "Hello".
                                          */
          {if not $greetingWord}
            Hello {$name}!
          {else}
            {$greetingWord} {$name}!
          {/if}
        {/template}

2.  Add a third template to the file. This template, `helloNames`, demonstrates
    a `for` loop with an `ifempty` command. It also shows how to call other
    templates and insert their output using the `call` command. Note that the
    `data="all"` attribute in the `call` command passes all of the caller's
    template data to the callee template.

        /** Greets a person and optionally a list of other people. */
        {template .helloNames}
          {@param name: string} /** The person's name. */
          {@param additionalNames: list<string>} /**
                                                  * Additional names to greet.
                                                  * May be an empty list.
                                                  */
          // Greet the person.
          {call .helloName data="all" /}<br>
          // Greet the additional people.
          {for $additionalName in $additionalNames}
            {call .helloName}
              {param name: $additionalName /}
            {/call}
            {if not isLast($additionalName)}
              <br>  // break after every line except the last
            {/if}
          {ifempty}
            No additional people to greet.
          {/for}
        {/template}

3.  Now edit `src/main/java/exampleHelloWorld.java` to call the new templates
    and exercise them with data:

         package example;

         import com.google.template.soy.SoyFileSet;
         import com.google.template.soy.tofu.SoyTofu;
         import java.util.Arrays;
         import java.util.HashMap;
         import java.util.List;
         import java.util.Map;

         public class HelloWorld {
           public static void main(String[] args) {
             SoyFileSet sfs = SoyFileSet
                 .builder()
                 .add(HelloWorld.class.getResource("simple.soy"))
                 .build();

             // helloWorld
             SoyTofu tofu = sfs.compileToTofu();
             System.out.println(
                 tofu.newRenderer("examples.simple.helloWorld").render());

             // For convenience, create another SoyTofu object that has a
             // namespace specified, so you can pass partial template names to
             // the newRenderer() method.
             SoyTofu simpleTofu = tofu.forNamespace("examples.simple");

             // helloName
             Map<String, Object> data = new HashMap<>();
             data.put("name", "Ana");
             System.out.println("-----------------");
             System.out.println(
                 simpleTofu.newRenderer(".helloName").setData(data).render());

             // helloNames
             List<String> additionalNames = Arrays.asList("Bob", "Cid", "Dee");
             data.put("additionalNames", additionalNames);
             System.out.println("-----------------");
             System.out.println(
                 simpleTofu.newRenderer(".helloNames").setData(data).render());
           }
         }

    This example exercises the `.helloName` template with a Java `Map` in which
    the parameter `name` is mapped to the string `Ana`. For the `.helloNames`
    template, the example maps the parameter `additionalNames` to a list of
    strings `Bob`, `Cid`, `Dee`.

4.  Run `mvn package` at the root of your project. You should see this message
    at standard out:

        Hello world!
        -----------------
        Hello Ana!
        -----------------
        Hello Ana!<br>Hello Bob!<br>Hello Cid!<br>Hello Dee!

## Using Guice

If your application uses [Guice](http://code.google.com/p/google-guice/), you
can inject Soy classes such as `SoyFileSet.Builder` instead of constructing them
yourself. Your Guice injector must contain `SoyModule`.

For example, if you used Guice, the Hello World example from the previous
section would start like this (with three additional import lines not shown):

~~~ {.prettyprint}
    // Create a Guice injector that contains the SoyModule and use it get a SoyFileSet.Builder.
    Injector injector = Guice.createInjector(new SoyModule());
    SoyFileSet.Builder sfsBuilder = injector.getInstance(SoyFileSet.Builder.class);

    // Bundle the Soy files for your project into a SoyFileSet.
    SoyFileSet sfs = sfsBuilder.add(new File("simple.soy")).build();
~~~

## Using SoyParseInfoGenerator

You might find it error-prone to type hard-coded strings for template names and
template parameters. If so, you can use `SoyParseInfoGenerator` to generate Java
constants for the template and parameter names in your templates. Follow the
steps below to use `SoyParseInfoGenerator` with the Hello World example:

1.  Download the latest version of `SoyParseInfoGenerator.jar` from
    [Maven Central](http://search.maven.org/#search%7Cga%7C1%7Ca%3A%22soy%22)
    and put it at the project root.

    Unlike the main Soy jar, `SoyParseInfoGenerator.jar` is executable, and can
    be run from the command line with `java -jar SoyParseInfoGenerator.jar`. The
    jar parses Soy files and generates Java classes that contain information
    such as template and parameter names. (Typically, you would want to run
    SoyParseInfoGenerator as part of a build step. This can be done with
    exec-maven-plugin for example, but it is outside the scope of this codelab.)

    Run `SoyParseInfoGenerator` with the following flags:

        $ java -jar SoyParseInfoGenerator.jar \
            --outputDirectory src/main/java/example \
            --javaPackage example \
            --javaClassNameSource filename \
            --srcs src/main/resources/example/simple.soy

    This step creates the file `src/main/java/example/SimpleSoyInfo.java`. This
    file contains mappings between the generated constants and their
    corresponding strings.

    Open `src/main/java/example/SimpleSoyInfo.java` and look at the constants
    that `SoyParseInfoGenerator` generated for each of the templates and their
    parameters. For example, the Java constant `HELLO_NAME` maps to a
    `SoyTemplateInfo` object that represents the `.helloName` template, and the
    constant `HELLO_NAME.NAME` maps to the `.helloName` template's parameter
    `name`.

2.  Edit `src.main/java/example/HelloWorld.java` to looks like this:

~~~ {.prettyprint}
package example;

import static example.SimpleSoyInfo.HELLO_NAME;
import static example.SimpleSoyInfo.HELLO_NAMES;
import static example.SimpleSoyInfo.HELLO_WORLD;

import com.google.template.soy.SoyFileSet;
import com.google.template.soy.tofu.SoyTofu;
import example.SimpleSoyInfo.HelloNameSoyTemplateInfo;
import example.SimpleSoyInfo.HelloNamesSoyTemplateInfo;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HelloWorld {
  public static void main(String[] args) {
    SoyFileSet sfs = SoyFileSet
        .builder()
        .add(HelloWorld.class.getResource("simple.soy"))
        .build();
    SoyTofu tofu = sfs.compileToTofu();
    System.out.println(tofu.newRenderer(SimpleSoyInfo.HELLO_WORLD).render());

    SoyTofu simpleTofu = tofu.forNamespace("examples.simple");
    Map<String, Object> data = new HashMap<>();
    data.put(HelloNameSoyTemplateInfo.NAME, "Ana");
    System.out.println(
        simpleTofu
            .newRenderer(SimpleSoyInfo.HELLO_NAME)
            .setData(data)
            .render());

    List<String> additionalNames = Arrays.asList("Bob", "Cid", "Dee");
    data.put(HelloNamesSoyTemplateInfo.ADDITIONAL_NAMES, additionalNames);
    System.out.println(
        simpleTofu
            .newRenderer(SimpleSoyInfo.HELLO_NAMES)
            .setData(data)
            .render());
  }
}
~~~

3.  Run `mvn package` at the root of your project. You should see the same
    message as before:

        Hello world!
        -----------------
        Hello Ana!
        -----------------
        Hello Ana!<br>Hello Bob!<br>Hello Cid!<br>Hello Dee!

You've just completed the Soy Hello World using Java. Where should you go next?

-   To use the same templates from this chapter in JavaScript, try the
    [Hello World Using JavaScript](helloworld_js.md) examples.
-   To read more about Soy concepts, take a look at the
    [Concepts](../concepts/index.md) chapter.

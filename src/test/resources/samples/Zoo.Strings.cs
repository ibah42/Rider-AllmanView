// Every shape a C# string literal can take, each one holding C# code that must not be parsed.
//
// This is not a contrived file: a source generator is exactly where whole programs live inside
// string literals, and it is where a brace counted by mistake does the most damage. Every
// literal below is valid C# -- braces are escaped the way each form requires, which differs
// between the forms and is half the reason this file exists.

using System;
using System.Text;

namespace Ivv.Codegen
{
    public static class Templates
    {
        // --- plain literals -------------------------------------------------------------

        public const string Hanging = "if (x) {";
        public const string WholeClass = "class Z { void N() { } }";
        public const string LooksLikeAComment = "// class NotDeclared { }";
        public const string LooksLikeABlockComment = "/* class NotDeclared { } */";
        public const string Apostrophe = "it's a brace: {";
        public const string Escaped = "quote \" then brace {";
        public static readonly byte[] Utf8 = "class Z {"u8.ToArray();

        // --- verbatim: a backslash is literal, a quote is doubled ------------------------

        public const string WindowsPath = @"C:\Projects\Zoo\{template}";
        public const string DoubledQuote = @"say ""hi"" and {";

        public const string MultiLineClass = @"
class Generated
{
    void Work()
    {
        if (ready)
        {
            Run();
        }
    }
}
";

        // --- interpolated: a literal brace is doubled ------------------------------------

        public static string Hole(string name) => $"class {name} {{";
        public static string Escapes => $"{{ not a hole }}";
        public static string TripleBrace(int x) => $"{{{x}}}";
        public static string FormatSpecifier(int value) => $"0x{value:X8} {{";
        public static string Alignment(int value) => $"{value,10} {{";
        public static string HoleHoldingAString(int x) => $"{string.Format("{0}", x)} {{";
        public static string HoleHoldingALambda(int[] xs) =>
            $"{string.Join(",", Array.ConvertAll(xs, x => { return x + 1; }))} {{";

        // --- interpolated verbatim, both orders ------------------------------------------

        public static string VerbatimFirst(string name) => @$"class {name}
{{
    void Work() {{ }}
}}
";

        public static string DollarFirst(string name) => $@"namespace {name}
{{
}}
";

        // --- raw string literals: nothing is escaped, the fence does the work -------------

        public const string Raw = """
            class Generated
            {
                void Work()
                {
                    Run();
                }
            }
            """;

        public const string RawHoldingQuotes = """"
            he said """not a fence""" and left a brace {
            """";

        public static string RawInterpolated(string name) => $"""
            class {name}
            """;

        // With two dollars a single brace is literal and a hole is opened by two, so a whole
        // C# body can sit inside an interpolated template without a single escape.
        public static string RawTwoDollars(string name) => $$"""
            class {{name}}
            {
                void Work()
                {
                    Run();
                }
            }
            """;

        public static string RawThreeDollars(string name) => $$$"""
            class {{{name}}}
            {
                var lookup = new Dictionary<string, string> {{ "a", "b" }};
            }
            """;

        // --- the blocks below are real, and every one of them must still be found ---------

        public static StringBuilder Render(string name)
        {
            var builder = new StringBuilder();

            foreach (var line in MultiLineClass.Split('\n'))
            {
                if (line.Length > 0)
                {
                    builder.AppendLine(line);
                }
            }

            return builder;
        }

        private sealed class Buffer
        {
            private readonly StringBuilder inner = new StringBuilder();

            public int Length
            {
                get
                {
                    return inner.Length;
                }
            }

            public void Add(string text)
            {
                inner.Append(text);
            }
        }
    }
}

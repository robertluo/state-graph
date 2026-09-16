{ pkgs, ... }:

{
  # The toolchain the suites need and nothing else — README.md names the same three
  # for anyone not using devenv. A JVM inherits its PATH at launch, so a REPL started
  # outside this shell cannot draw, however current the shell is; see AGENTS.md.
  packages = [
    pkgs.git
    pkgs.jdk21
    pkgs.clojure
    # `dot`, for robertluo.state-graph.check/draw! — and for the drawing tests, which
    # render for real from the fast suite.
    pkgs.graphviz
  ];

  # The release gate, as the author's other libraries spell it: clean, both suites, the
  # jar in target/. See build.clj.
  enterTest = ''
    clojure -T:build ci
  '';
}

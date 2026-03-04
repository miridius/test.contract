# griffin.test.contract

A Clojure/ClojureScript generative testing library for contract-based testing. Defines contracts that unify integration tests and mocks — both real implementations and mocks satisfy the same contract.

## Prerequisites

- Java 21+ (OpenJDK)
- Clojure CLI (`clojure` command) — install via: `curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh && chmod +x linux-install.sh && ./linux-install.sh --prefix $HOME/.local`
- Ensure `$HOME/.local/bin` is on PATH

## Dependencies

Dependencies are vendored in `.deps/` as local roots to avoid Maven network issues in sandboxed environments. No network access is needed for building or testing.

## Common Commands

### Run tests
```bash
clojure -M:test
```

### Start a REPL
```bash
clojure -M
```

### Start a REPL with test deps
```bash
clojure -M:test -r
```

### Evaluate an expression
```bash
clojure -M -e '(require (quote griffin.test.contract)) (println "loaded")'
```

### Print classpath
```bash
clojure -Spath
```

## Project Structure

```
src/griffin/test/
├── contract.cljc           # Main API: model, method, return, mock, verify, test-proxy, test-model
├── contract/
│   ├── protocol.cljc       # Core protocols: Return, Method, Model
│   └── mock.cljc            # Mock state protocol: State
test/griffin/test/
└── contract_test.clj        # Test suite (6 tests)
```

## Key Concepts

- **Model**: Defines the contract — a set of protocols, methods, initial state, and generators
- **Method**: Defines a single operation with args spec, preconditions, and return spec
- **Return**: Specifies expected return value spec and state transitions
- **Mock**: Auto-generated mock implementations from a model definition
- **verify**: Property-based test that checks a real implementation satisfies the contract
- **test-proxy**: Verifies real impl by running random calls and comparing to model

## Code Style

- All source files use `.cljc` extension (cross-platform Clojure/ClojureScript)
- Test files use `.clj` extension (Clojure-only)
- Namespace: `griffin.test.contract` (main), `griffin.test.contract.protocol`, `griffin.test.contract.mock`
- Uses `clojure.spec.alpha` for specs and `clojure.test.check` for generative testing

## Environment Note

When running in sandboxed environments where Java's Maven resolver cannot reach Maven Central directly (DNS resolution fails through proxies), use `JAVA_TOOL_OPTIONS=""` to clear proxy settings since deps are vendored locally.

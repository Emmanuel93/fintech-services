# Language convention

**All code is written in English. No exceptions beyond the three listed below.**

This covers every identifier the team controls: classes, interfaces, enums and their constants,
methods, fields, parameters, local variables, test names, package names, Kafka topic names, and
configuration keys.

---

## Why it is stated, and not left to taste

A mixed codebase is not half-English: it is a codebase where **every name is a coin toss**. Someone
looking for the method that shifts a due date has to try `shiftDueDate`, `correrVencimiento` and
`moveDueDate` before finding it. The cost is not aesthetic — it is that people stop searching and
start re-implementing.

## The three exceptions, and why each one is not negotiable

### 1. External provider contracts

Field names that travel on the wire to a third party keep **their** spelling, because they are not
ours to choose:

```java
/** Cuerpo de PUT ordenPago/registra. Nombres en español porque son los del contrato de STP. */
record RegistraOrdenPagoRequest(String claveRastreo, String conceptoPago, …)
```

Renaming `claveRastreo` to `trackingKey` in that record does not tidy anything — it **breaks the
payment**. The rule applies to the code we own, not to the shape of someone else's API.

Where this applies today: the STP DTOs under `infrastructure/adapter/out/http/dto`.

### 2. Regulatory and market terms with no English equivalent

`CLABE`, `CURP`, `RFC`, `CEP`, `cadena original`, `clave de rastreo`, `folio fiscal`. These are
proper nouns of the Mexican financial system. Translating `CLABE` to `StandardizedBankingCode`
would make the code *less* clear to anyone who has to reconcile it against a bank statement.

### 3. Comments and documentation

Comments in this repository are written in Spanish, and they carry the reasoning — why a guard
exists, which defect it prevents, what was tried before. That is the team's working language and
the rule above does not touch it.

> The distinction is deliberate: **identifiers are read by tools and by strangers; comments are read
> by the team.** A name has to be findable; an explanation has to be understood.

---

## Migrating what is already in Spanish

Not every rename is safe, and the unsafe ones fail **silently**:

| Kind | Risk | How to do it |
|---|---|---|
| Class, private method, local, test name | none | rename directly |
| Public method on a serialized `record` | 🔴 the method name **is** the JSON field: renaming changes the wire contract between services | rename only with a coordinated deploy, or keep the wire name with `@JsonProperty` |
| JPA field **without** `@Column` | 🔴 the column name is derived from the field: renaming points the mapping at a column that does not exist | add `@Column(name = "<existing name>")` **first**, then rename the field |
| JPA field **with** `@Column` | none | rename the field; the annotation pins the column |

Measured at the time of writing: **66** Spanish fields in entities, **35** of them without an
explicit `@Column`, and **7** Spanish fields in serialized records.

---

## Status of the migration

| Tranche | Done | Left |
|---|---|---|
| Class names | 39 renamed | 12 STP/Círculo provider contracts, kept on purpose |
| Method names | 238 renamed | 0 outside provider contracts |
| Local variables | ~300 occurrences | 0 outside provider-mirroring code |
| Test package names | 7 renamed | 0 |
| Enum constants | `RuleType.MORA_CHECK` → `WORST_ARREARS_DAYS`, `Dimension`, `Unit`, `KycProvider.NONE` | `BanxicoResponseCode` (Banxico's own catalogue) |
| Notification template variables | 6 renamed, templates migrated | 0 |
| JPA fields | 0 | 35 without `@Column` — need the annotation added first |
| Serialized `record` fields | 1 (`TraceResponse`, no consumer outside Java) | the backoffice BFF views — see below |
| Comments | 0 | ~930 files — exception 3 above |

### The backoffice BFF is the tranche that cannot be done from here

`BackofficeViews` and `AccountingController` build their JSON with explicit keys — `tasaAnual`,
`plazoMeses`, `tipoProspecto`, `folioFiscal`, `carteraVencida`. **The consumer is the backoffice
front-end, which does not live in this repository.** Renaming those keys compiles, passes every test
in this repo, and breaks the screen. They stay until the rename can be coordinated with that code.

## What went wrong doing it, and what it teaches

Three classes of silent breakage showed up, in increasing order of nastiness:

1. **The compiler catches a renamed accessor.** `CdcRespuesta.creditos()` and `Detalle.sello()` broke
   the build the moment their call sites moved. Cheap to find, cheap to fix.
2. **The compiler does not read string literals.** A pass that renamed identifiers also rewrote the
   words inside Spanish prose: an OpenAPI description became *"Tesorería: accounts propias, por dónde
   sale cada payment"*, and a `@Operation(summary=…)` became *"Obtener account(s) de crédito"*. Six
   files were corrupted this way and every test stayed green.
3. **Java text blocks defeat naive literal masking.** `"""…"""` is not matched by a `"…"` regex, so a
   variable used right after a text block was skipped while its declaration was renamed.

The check that actually works is not a smarter regex — it is **comparing the multiset of string
literals and of comment lines before and after**. Anything that changed there is either deliberate or
a bug, and there is no third case:

```bash
# for each changed file: extract every "…" and every """…""" from HEAD and from the working tree,
# and report any literal that appears in one and not the other.
```

That check found all six corrupted files. Nothing else did.

### The fourth one: a method name written as a string

`@MethodSource("proveedores")` names its factory method **as text**. Renaming the method left seven
annotations pointing at a name that no longer existed, and the whole class died with
`initializationError` — not a failed assertion, a class that never ran. It only showed up on a full
suite run, because `compileTestJava` has nothing to check.

Anything that names code with a string is exposed the same way: `@MethodSource`, `@Qualifier`,
`@Bean("…")`, `@DependsOn`, SpEL in `@Value` / `@PreAuthorize` / `@Retryable(exceptionExpression=…)`,
`@Sql`. The check is mechanical and worth keeping:

```bash
# every @MethodSource("x") must have a method x( in the same file
python3 - <<'EOF'
import glob, re
for f in glob.glob("**/src/**/*.java", recursive=True):
    s = open(f).read()
    for m in re.finditer(r'@MethodSource\(\s*"([^"#]+)"\s*\)', s):
        if not re.search(r'\b' + re.escape(m.group(1)) + r'\s*\(', s):
            print(f, m.group(1))
EOF
```

> This is also the argument against SpEL that names a bean: the session already replaced
> `exceptionExpression = "#{@scoreRequestedEventListener.isTransient(#root)}"` with
> `retryFor = BureauReportPending.class` for the same reason. **A reference the compiler can see is
> a reference a rename cannot break.**

### And one mistranslation the compiler could never catch

`estado` in an address record is the **state**, not the status. A rename map that maps
`estado → status` turns `AddressParts(…, String estado, …)` into `AddressParts(…, String status, …)`
and every test still passes, because the type is `String` either way. Word maps need to be read in
context, not applied.

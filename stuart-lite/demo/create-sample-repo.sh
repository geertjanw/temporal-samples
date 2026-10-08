#!/usr/bin/env bash
# Creates a tiny git repo for Stuart to work on: /tmp/stuart-sample-repo
set -euo pipefail
REPO=${1:-/tmp/stuart-sample-repo}
rm -rf "$REPO" && mkdir -p "$REPO/src/main/java/com/acme/agreements" "$REPO/src/test/java/com/acme/agreements"
cd "$REPO"

cat > AGENTS.md <<'EOF'
# Agreements service - notes for agents
- Plain Java 21, no frameworks. Records for DTOs.
- Every business rule is marked with an anchor comment like `// [AGR-1] ...` that matches the spec id.
- Tests: JUnit 5, one test class per production class, method names in snake_case.
EOF

cat > src/main/java/com/acme/agreements/Agreement.java <<'EOF'
package com.acme.agreements;

import java.math.BigDecimal;

public record Agreement(String id, String customerId, BigDecimal amount) {
}
EOF

cat > src/main/java/com/acme/agreements/AgreementResponse.java <<'EOF'
package com.acme.agreements;

import java.math.BigDecimal;

public record AgreementResponse(String id, BigDecimal amount) {
}
EOF

cat > src/main/java/com/acme/agreements/AgreementService.java <<'EOF'
package com.acme.agreements;

import java.util.List;

public class AgreementService {

    // [AGR-1] Return all agreements of a customer
    public List<AgreementResponse> findByCustomer(List<Agreement> all, String customerId) {
        return all.stream()
                .filter(a -> a.customerId().equals(customerId))
                .map(a -> new AgreementResponse(a.id(), a.amount()))
                .toList();
    }
}
EOF

cat > src/test/java/com/acme/agreements/AgreementServiceTest.java <<'EOF'
package com.acme.agreements;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgreementServiceTest {

    @Test
    void returns_only_agreements_of_customer() {
        var all = List.of(new Agreement("1", "c1", BigDecimal.TEN), new Agreement("2", "c2", BigDecimal.ONE));
        assertEquals(1, new AgreementService().findByCustomer(all, "c1").size());
    }
}
EOF

git init -q -b main
git add -A
git -c user.name=demo -c user.email=demo@localhost commit -qm "Initial commit"
echo "Sample repo ready at $REPO"

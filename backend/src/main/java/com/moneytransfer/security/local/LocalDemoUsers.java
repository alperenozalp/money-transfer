package com.moneytransfer.security.local;

import java.util.List;

final class LocalDemoUsers {

        static final List<DemoUser> ALL = List.of(
                        new DemoUser(
                                        "demo-customer",
                                        "demo-customer",
                                        "Demo User 1",
                                        "demo1234",
                                        List.of(new DemoAccount("TR000000000000000000000001", "125000.00"))),
                        new DemoUser(
                                        "demo-recipient",
                                        "demo-recipient",
                                        "Demo User 2",
                                        "transfer1234",
                                        List.of(new DemoAccount("TR000000000000000000000003", "5000.00"))),
                        new DemoUser(
                                        "demo-customer-3",
                                        "demo-customer-3",
                                        "Demo User 3",
                                        "money1234",
                                        List.of(new DemoAccount("TR000000000000000000000004", "40000.00"))));

        private LocalDemoUsers() {
        }

        record DemoUser(
                        String customerId,
                        String subject,
                        String displayName,
                        String password,
                        List<DemoAccount> accounts) {
        }

        record DemoAccount(String iban, String balance) {
        }
}

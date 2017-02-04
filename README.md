# quartz-spring-integration

Just annotate your class with custom `@Job` annotation and the `QuartzConfig` class will do the rest: register jobs details and triggers automatically. No need to couple the business code with Quartz interfaces/classes. Automatically remove unused/renamed jobs from Quartz's tables.

Based on https://github.com/davidkiss/spring-boot-quartz-demo

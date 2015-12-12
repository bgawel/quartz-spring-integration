package springquartz.job;

import org.slf4j.Logger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springquartz.scheduler.Job;

import static org.slf4j.LoggerFactory.getLogger;

@Job(cron = "${job3.cron:0 0/5 * * * ?}", executableMethod = "doIt")
public class Job3 {

    private static final Logger log = getLogger(Job3.class);

    public void doIt() {
        log.info("Executing Job3");
    }

    @Configuration
    static class Config {

        @Bean
        Job3 job3() {
            return new Job3();
        }
    }
}

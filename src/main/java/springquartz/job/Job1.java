package springquartz.job;

import org.slf4j.Logger;
import org.springframework.stereotype.Component;
import springquartz.scheduler.Job;

import static org.slf4j.LoggerFactory.getLogger;

@Component
@Job(cron = "0 0/1 * * * ?")
public class Job1 {

    private static final Logger log = getLogger(Job1.class);

    public void execute() {
        log.info("Executing Job1");
    }
}

package springquartz.job;

import org.slf4j.Logger;
import org.springframework.stereotype.Component;
import springquartz.scheduler.Job;

import static org.slf4j.LoggerFactory.getLogger;

@Component
@Job(cron = "0 0/1 * * * ?")
public class Job2 {

    private static final Logger log = getLogger(Job2.class);

    public int execute() {
        log.info("Executing Job2");
        return 666;
    }
}

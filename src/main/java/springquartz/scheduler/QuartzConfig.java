package springquartz.scheduler;

import com.google.common.collect.ImmutableMap;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.spi.JobFactory;
import org.quartz.spi.TriggerFiredBundle;
import org.slf4j.Logger;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.PropertiesFactoryBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.quartz.CronTriggerFactoryBean;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.reducing;
import static java.util.stream.Collectors.toList;
import static org.quartz.impl.matchers.GroupMatcher.jobGroupEquals;
import static org.slf4j.LoggerFactory.getLogger;

@Configuration
@ConditionalOnProperty(name = "quartz.enabled")
class QuartzConfig {

    private static final Logger log = getLogger(QuartzConfig.class);

    @Bean
    public JobFactory jobFactory(final ApplicationContext applicationContext) {
        return new QuartzJobDetailFactory(applicationContext);
    }

    @Bean
    SchedulerFactoryBean schedulerFactoryBean(final DataSource dataSource,
                                              final JobFactory jobFactory,
                                              final ApplicationContext applicationContext,
                                              final Environment environment) throws IOException {
        List<Trigger> triggers = createJobs(applicationContext, environment);

        SchedulerFactoryBean factory = new SelfCleaningUpScheduler(getJobKeys(triggers));
        // this allows to update triggers in DB when updating settings in config file:
        factory.setOverwriteExistingJobs(true);
        factory.setDataSource(dataSource);
        factory.setQuartzProperties(quartzProperties());
        factory.setJobFactory(jobFactory);
        factory.setTriggers(triggers.toArray(new Trigger[triggers.size()]));
        return factory;
    }

    private List<JobKey> getJobKeys(final List<Trigger> triggers) {
        return triggers
                .stream()
                .map(Trigger::getJobKey)
                .collect(toList());
    }

    private Properties quartzProperties() throws IOException {
        PropertiesFactoryBean propertiesFactoryBean = new PropertiesFactoryBean();
        propertiesFactoryBean.setLocation(new ClassPathResource("/quartz.properties"));
        propertiesFactoryBean.afterPropertiesSet();
        return propertiesFactoryBean.getObject();
    }

    private List<Trigger> createJobs(final ApplicationContext applicationContext, final Environment environment) {
        List<Trigger> triggers = new ArrayList<>();
        applicationContext.getBeansWithAnnotation(Job.class).forEach((name, fromBusinessJob) -> triggers.add(createJob(fromBusinessJob, environment)));
        if (triggers.isEmpty()) {
            log.warn("No jobs added. Did you annotate your jobs with @Job?");
        }
        return triggers;
    }

    private Trigger createJob(final Object fromBusinessJob, final Environment environment) {
        Job businessJobSpec = fromBusinessJob.getClass().getAnnotation(Job.class);

        JobDetailFactoryBean jobDetail = createJobDetail(fromBusinessJob.getClass(), businessJobSpec.executableMethod());
        CronTriggerFactoryBean trigger = createTrigger(jobDetail.getObject(), fromBusinessJob.getClass(),
				environment.resolvePlaceholders(businessJobSpec.cron()));

        log.info("Created a job {}", fromBusinessJob.getClass());
        return trigger.getObject();
    }

    private JobDetailFactoryBean createJobDetail(final Class<?> businessJobClass, final String executableMethod) {
        JobDetailFactoryBean factoryBean = new JobDetailFactoryBean();
        factoryBean.setJobClass(QuartzJob.class);
        factoryBean.setJobDataAsMap(ImmutableMap.of(
                "businessJob.class", businessJobClass,
                "businessJob.method", executableMethod));
        // job has to be durable to be stored in DB:
        factoryBean.setDurability(true);
        initBean(factoryBean, businessJobClass.getSimpleName() + "Detail");
        return factoryBean;
    }

    private CronTriggerFactoryBean createTrigger(final JobDetail jobDetail,
                                                 final Class<?> businessJobClass,
                                                 final String cronExpression) {
        CronTriggerFactoryBean factoryBean = new CronTriggerFactoryBean();
        factoryBean.setJobDetail(jobDetail);
        factoryBean.setCronExpression(cronExpression);
        factoryBean.setStartDelay(0L);
        initBean(factoryBean, businessJobClass.getSimpleName() + "Trigger");
        return factoryBean;
    }

    private void initBean(final Object initializingNameAwareBean, final String beanName) {
        try {
            ((BeanNameAware) initializingNameAwareBean).setBeanName(beanName);
            ((InitializingBean) initializingNameAwareBean).afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DisallowConcurrentExecution
    public static class QuartzJob implements org.quartz.Job, ApplicationContextAware {

        private ApplicationContext applicationContext;

        @Override
        public void execute(final JobExecutionContext context) throws JobExecutionException {
            Class<?> clazz = (Class<?>) context.getMergedJobDataMap().get("businessJob.class");
            String method = (String) context.getMergedJobDataMap().get("businessJob.method");
            Object bean = applicationContext.getBean(clazz);
            try {
                ofNullable(clazz.getMethod(method).invoke(bean)).ifPresent(context::setResult);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void setApplicationContext(final ApplicationContext context) {
            applicationContext = context;
        }
    }

    private static class QuartzJobDetailFactory extends SpringBeanJobFactory {

        private final ApplicationContext applicationContext;

        QuartzJobDetailFactory(final ApplicationContext applicationContext) {
            this.applicationContext = applicationContext;
        }

        @Override
        protected Object createJobInstance(final TriggerFiredBundle bundle) throws Exception {
            final Object job = super.createJobInstance(bundle);
            ((QuartzJob) job).setApplicationContext(applicationContext);
            return job;
        }
    }

    private static class SelfCleaningUpScheduler extends SchedulerFactoryBean {

        private final List<JobKey> currentJobKeys;

        SelfCleaningUpScheduler(final List<JobKey> currentJobKeys) {
            this.currentJobKeys = currentJobKeys;
        }

        @Override
        public void afterPropertiesSet() throws Exception {
            super.afterPropertiesSet();
            deleteOrphanedJobs();
        }

        private void deleteOrphanedJobs() throws SchedulerException {
            Scheduler scheduler = getScheduler();
            for (String groupName : scheduler.getJobGroupNames()) {
                for (JobKey jobKey : scheduler.getJobKeys(jobGroupEquals(groupName))) {
                    if (!currentJobKeys.contains(jobKey)) {
                        scheduler.deleteJob(jobKey);
                        log.info("Deleted orphaned job: {}", jobKey);
                    }
                }
            }
        }
    }
}

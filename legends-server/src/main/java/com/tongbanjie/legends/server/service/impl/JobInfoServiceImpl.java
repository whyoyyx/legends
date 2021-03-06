package com.tongbanjie.legends.server.service.impl;

import java.text.ParseException;
import java.util.Date;
import java.util.List;

import javax.annotation.Resource;

import com.tongbanjie.legends.server.component.job.CoreJob;
import com.tongbanjie.legends.server.utils.HttpClientUtils;
import com.tongbanjie.legends.server.utils.Result;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateUtils;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.tongbanjie.legends.client.enums.MethodFlag;
import com.tongbanjie.legends.client.model.JobRequest;
import com.tongbanjie.legends.client.model.JobTestResponse;
import com.tongbanjie.legends.server.component.SchedulerWrapper;
import com.tongbanjie.legends.server.dao.JobInfoDAO;
import com.tongbanjie.legends.server.dao.JobInfoHistoryDAO;
import com.tongbanjie.legends.server.dao.dataobject.JobInfo;
import com.tongbanjie.legends.server.dao.dataobject.JobInfoHistory;
import com.tongbanjie.legends.server.dao.dataobject.enums.JobInfoTypeEnum;
import com.tongbanjie.legends.server.service.JobInfoService;

/**
 * @author chen.jie
 */
@Service("jobInfoServiceImpl")
public class JobInfoServiceImpl implements JobInfoService {

	private static final Logger LOG = LoggerFactory.getLogger(JobInfoServiceImpl.class);

	@Resource
	private JobInfoDAO jobInfoDAO;

	@Resource
	private JobInfoHistoryDAO jobInfoHistoryDAO;

	@Resource
	private SchedulerWrapper schedulerWrapper;

	@Override
	public Result<Long> addJobInfo(JobInfo jobInfo) {
		Result<Long> result = new Result<Long>();

		if (!checkJobInfo(jobInfo)) {
			result.setSuccess(false);
			result.setErrorMsg("参数不合法！");
			return result;
		}

		// 判断name、group组合成的key是否唯一
		Result<List<JobInfo>> hasExists = selectListByNameAndGroup(jobInfo.getName(), jobInfo.getGroup());
		if (hasExists.isSuccess() && CollectionUtils.isNotEmpty(hasExists.getData())) {
			result.setSuccess(false);
			result.setErrorMsg("任务名称和任务分组合成的key已经存在，请重新输入！");
			return result;
		}

		try {
			trim(jobInfo);
			int i = jobInfoDAO.insert(jobInfo);
			if (i > 0) {
				// if activated,create
				if (jobInfo.isActivity() == true) {
					JobDetail jobDetail = schedulerWrapper.createJobDetailByJobInfo(jobInfo);
					Trigger trigger = schedulerWrapper.createCronTrigger(jobInfo);
					schedulerWrapper.scheduleJob(jobDetail, trigger);
				}
				result.setSuccess(true);
				result.setData(jobInfo.getId());
			} else {
				result.setSuccess(false);
				result.setErrorMsg("任务添加失败，请稍后重试！");
			}
		} catch (Exception e) {
			LOG.error("Legends SysException: ", e);
			if (e.getCause() instanceof ParseException) {
				ParseException parseExc = (ParseException) e.getCause();
				result.setErrorMsg(parseExc.getMessage());
			} else {
				result.setErrorMsg("系统异常，请联系开发人员！");
			}
			result.setSuccess(false);
		}

		return result;
	}

	@Override
	public Result<Boolean> deleteJobInfoById(long id) {
		Result<Boolean> result = new Result<Boolean>();

		try {
			JobInfo jobInfo = jobInfoDAO.findById(id);
			if (jobInfo != null) {
				jobInfoDAO.deleteById(id);

				JobInfoHistory jobInfoHistory = copyFromJobInfo(jobInfo);
				jobInfoHistoryDAO.insertJobInfoHistory(jobInfoHistory);

				JobKey jobKey = schedulerWrapper.getJobKeyByJobInfo(jobInfo);
				schedulerWrapper.deleteJob(jobKey);
			}
			result.setSuccess(true);
			result.setData(Boolean.TRUE);
		} catch (Exception e) {
			LOG.error("Legends SysException: ", e);
			result.setSuccess(false);
			result.setErrorMsg("系统异常，请联系开发人员！");
		}

		return result;
	}

	@Override
	public Result<Long> updateJobInfo(JobInfo jobInfo) {
		Result<Long> result = new Result<Long>();

		try {
			trim(jobInfo);
			JobInfo oldJobInfo = jobInfoDAO.findById(jobInfo.getId());

			int i = jobInfoDAO.updateById(jobInfo);
			if (i > 0) {
				// if not activated,deleteJob;
				// if activated:
				// 1.scheduler doesn't contains JobDetail,createJob;
				// 2.scheduler contains JobDetail:
				// 2.1.cron changes,rescheduleJob
				// 2.2.cron doesn't changes,do nothing;
				if (jobInfo.isActivity() == true) {
					if (schedulerWrapper.containsJobDetail(oldJobInfo)) {
						if (isChangedCron(oldJobInfo, jobInfo)) {
							TriggerKey triggerKey = schedulerWrapper.getTriggerKeyByJobInfo(jobInfo);
							Trigger newTrigger = schedulerWrapper.createCronTrigger(jobInfo);
							schedulerWrapper.rescheduleJob(triggerKey, newTrigger);
						}
						result.setSuccess(true);
						result.setData(jobInfo.getId());
					} else {
						// createJob
						JobDetail jobDetail = schedulerWrapper.createJobDetailByJobInfo(jobInfo);
						Trigger trigger = schedulerWrapper.createCronTrigger(jobInfo);
						schedulerWrapper.scheduleJob(jobDetail, trigger);
						result.setSuccess(true);
						result.setData(jobInfo.getId());
					}
				} else {
					// deleteJob
					JobKey jobKey = schedulerWrapper.getJobKeyByJobInfo(jobInfo);
					schedulerWrapper.deleteJob(jobKey);
					result.setSuccess(true);
					result.setData(jobInfo.getId());
				}
			} else {
				result.setSuccess(false);
				result.setErrorMsg("任务更新失败，请稍后重试！");
			}
		} catch (Exception e) {
			LOG.error("Legends SysException: ", e);
			if (e.getCause() instanceof ParseException) {
				ParseException parseExc = (ParseException) e.getCause();
				result.setErrorMsg(parseExc.getMessage());
			} else {
				result.setErrorMsg("系统异常，请联系开发人员！");
			}
			result.setSuccess(false);
		}

		return result;
	}

	@Override
	public Result<JobInfo> selectJobInfoById(long id) {
		Result<JobInfo> result = new Result<JobInfo>();

		if (id <= 0L) {
			result.setSuccess(false);
			result.setErrorMsg("参数不合法！");
			return result;
		}

		try {
			JobInfo jobInfo = jobInfoDAO.findById(id);
			result.setSuccess(true);
			result.setData(jobInfo);
		} catch (Exception e) {
			LOG.error("Legends SysException: ", e);
			result.setSuccess(false);
			result.setErrorMsg("系统异常，请联系开发人员！");
		}

		return result;
	}

	@Override
	public Result<List<JobInfo>> selectList(JobInfo jobInfo) {
		Result<List<JobInfo>> result = new Result<List<JobInfo>>();

		try {
			List<JobInfo> jobInfoList = jobInfoDAO.getList(jobInfo);
			result.setSuccess(true);
			result.setData(jobInfoList);
		} catch (Exception e) {
			LOG.error("Legends SysException: ", e);
			result.setSuccess(false);
			result.setErrorMsg("系统异常，请联系开发人员！");
		}

		return result;
	}

	@Override
	public Result<List<JobInfo>> selectListByNameAndGroup(String name, String group) {
		Result<List<JobInfo>> result = new Result<List<JobInfo>>();

		try {
			List<JobInfo> jobInfoList = jobInfoDAO.getListByNameAndGroup(name, group);
			result.setSuccess(true);
			result.setData(jobInfoList);
		} catch (Exception e) {
			LOG.error("Legends SysException: ", e);
			result.setSuccess(false);
			result.setErrorMsg("系统异常，请联系开发人员！");
		}

		return result;
	}

	/**
	 * 参数校验，true为合法；false为不合法
	 *
	 * @param jobInfo
	 * @return
	 */
	private boolean checkJobInfo(JobInfo jobInfo) {
		if (StringUtils.isBlank(jobInfo.getClassPath())) {
			return false;
		}
		if (jobInfo.getType().equals(JobInfoTypeEnum.ONCE) && jobInfo.getTime() == null) {
			return false;
		}

		if (jobInfo.getType().equals(JobInfoTypeEnum.ONCE) && jobInfo.getTime().before(new Date())) {
			return false;
		}

		if (jobInfo.getType().equals(JobInfoTypeEnum.REPEAT) && StringUtils.isBlank(jobInfo.getCron())) {
			return false;
		}

		if (StringUtils.isBlank(jobInfo.getUrls())) {
			return false;
		}
		return true;
	}

	private JobInfoHistory copyFromJobInfo(JobInfo jobInfo) {
		JobInfoHistory jobInfoHistory = new JobInfoHistory();
		BeanUtils.copyProperties(jobInfo, jobInfoHistory);
		jobInfoHistory.setId(null);
		jobInfoHistory.setJobInfoId(jobInfo.getId());
		return jobInfoHistory;
	}

	/**
	 * cron表达式是否有变更，只有表达式变更了才会触发Job更新操作
	 *
	 * @param oldJobInfo
	 * @param jobInfo
	 * @return
	 */
	private boolean isChangedCron(JobInfo oldJobInfo, JobInfo jobInfo) {
		if (jobInfo.getType().equals(JobInfoTypeEnum.ONCE)) {
			if (jobInfo.getTime().equals(oldJobInfo.getTime())) {
				return false;
			} else {
				return true;
			}
		} else if (jobInfo.getType().equals(JobInfoTypeEnum.REPEAT)) {
			if (oldJobInfo.getCron().equals(jobInfo.getCron())) {
				return false;
			} else {
				return true;
			}
		} else {
			// TODO
			return false;
		}
	}

	private void trim(JobInfo jobInfo) {
		if (jobInfo.getName() != null) {
			jobInfo.setName(jobInfo.getName().trim());
		}
		if (jobInfo.getGroup() != null) {
			jobInfo.setGroup(jobInfo.getGroup().trim());
		}
		if (jobInfo.getCron() != null) {
			jobInfo.setCron(jobInfo.getCron().trim());
		}
		if (jobInfo.getUrls() != null) {
			jobInfo.setUrls(jobInfo.getUrls().trim());
		}
		if (jobInfo.getClassPath() != null) {
			jobInfo.setClassPath(jobInfo.getClassPath().trim());
		}
		if (jobInfo.getOwnerPhone() != null) {
			jobInfo.setOwnerPhone(jobInfo.getOwnerPhone().trim());
		}
	}

	@Override
	public Result<Boolean> testUrlsAndClassPath(String urls, String classPath) {
		Result<Boolean> r = new Result<Boolean>();

		try {
			String[] urlArray = urls.split(",");
			for (String url : urlArray) {
				try {
					JobRequest jr = new JobRequest();
					jr.setMethodFlag(MethodFlag.TEST);
					jr.setClassFullPath(classPath);
					String json = JSON.toJSONString(jr);
					String resJson = HttpClientUtils.post(url, json, "application/json", "utf-8", 5000, 5000);
					LOG.info("Test action response : " + resJson);
					JobTestResponse jtr = JSONObject.parseObject(resJson, JobTestResponse.class);
					if (!jtr.isSuccess()) {
						// 某一条失败了.
						r.setSuccess(false);
						r.setErrorMsg(url + ": " + jtr.getResult());
						return r;
					}
				} catch (Exception e) {
					LOG.error("Test action fail.", e);
					// 可能连接失败.
					r.setSuccess(false);
					r.setErrorMsg(url + ": 无效");
					return r;
				}
			}
			// 所有都测试成功.
			r.setSuccess(true);
			r.setData(true);
			return r;
		} catch (Exception e) {
			LOG.error("", e);
			r.setSuccess(false);
			r.setErrorMsg("系统异常，请联系开发人员！");
			return r;
		}
	}

	@Override
	public Result<Void> execute(JobInfo jobInfo) {

		try {

			if (!jobInfo.isActivity()) {
				return Result.buildFail(null, "请将任务开启！");
			}


			if (!checkJobInfo(jobInfo)) {
				return Result.buildFail(null, "参数不合法！");
			}

			String name = jobInfo.getName();
			String group = jobInfo.getGroup();
			name = name + System.currentTimeMillis();

			JobDetail jobDetail = schedulerWrapper.createJobDetail(jobInfo.getId(), name, group);
			jobDetail.getJobDataMap().put(CoreJob.IS_TEMPORARY_EXECUTE, true);

			Trigger trigger = schedulerWrapper.createNowTrigger(name, group);
			schedulerWrapper.scheduleJob(jobDetail, trigger);

			return Result.buildSucc(null);

		} catch (SchedulerException e) {
			LOG.error("", e);
			return Result.buildFail(null, "系统异常，请联系开发人员！");
		}

	}
}

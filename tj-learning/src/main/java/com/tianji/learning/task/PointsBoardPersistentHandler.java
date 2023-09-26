package com.tianji.learning.task;/*
 *@author 周欢
 *@version 1.0
 */

import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.learning.constants.RedisContstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.po.PointsBoardSeason;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.tianji.learning.service.IPointsBoardService;
import com.tianji.learning.utils.TableInfoContext;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.tianji.learning.constants.LearningContstants.POINTS_BOARD_TABLE_PREFIX;

@Slf4j
@Component
@RequiredArgsConstructor
public class PointsBoardPersistentHandler {

    private final IPointsBoardSeasonService seasonService;

    private final IPointsBoardService pointsBoardService;

    private final StringRedisTemplate redisTemplate;


//    @Scheduled(cron = "0 0 3 1 * ?") // 每月1号，凌晨3点执行
    @XxlJob("createTableJob")
    public void createPointsBoardTableOfLastSeason(){
        // 1.获取上月时间
        LocalDate time = LocalDate.now().minusMonths(1);
        // 2.查询赛季id
//        Integer season = seasonService.querySeasonByTime(time);
        PointsBoardSeason season = seasonService.lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, time)
                .ge(PointsBoardSeason::getEndTime, time)
                .one();
        log.debug("上赛季信息 {}",season);
        if (season == null) {
            // 赛季不存在
            return;
        }
        // 3.创建表
        pointsBoardService.createPointsBoardTableBySeason(season.getId());
    }

    @XxlJob("savePointsBoard2DB")
    public void savePointsBoard2DB(){
        // 1.获取上月时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        // 2.计算动态表名
        // 2.1.查询赛季信息
        Integer season = seasonService.querySeasonByTime(time);
        log.debug("上个赛季为：{}",season);
        if(season==null){
            return;
        }
        // 2.2.将表名存入ThreadLocal
        TableInfoContext.setInfo(POINTS_BOARD_TABLE_PREFIX + season);
        // 3.查询榜单数据
        // 3.1.拼接KEY
        String key = RedisContstants.POINTS_BOARD_KEY_PREFIX + time.format(DateTimeFormatter.ofPattern("yyyyMM"));
        int index = XxlJobHelper.getShardIndex();//当前分片的索引 从0开始
        int shardTotal=XxlJobHelper.getShardTotal();//总分片数

        // 3.2.查询数据
        int pageNo = index+1;
        int pageSize = 1000;
        while (true) {
            List<PointsBoard> boardList = pointsBoardService.queryCurrentBoard(key, pageNo, pageSize);
            if (CollUtils.isEmpty(boardList)) {
                break;
            }
            // 4.持久化到数据库
            // 4.1.把排名信息写入id
            boardList.forEach(b -> {
                b.setId(b.getRank().longValue());
                b.setRank(null);
            });
            // 4.2.持久化
            pointsBoardService.saveBatch(boardList);
            // 5.翻页
            pageNo+=shardTotal;
        }
        // 任务结束，移除动态表名
        TableInfoContext.remove();
    }

    @XxlJob("clearPointsBoardFromRedis")
    public void clearPointsBoardFromRedis(){
        // 1.获取上月时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        // 2.计算key
        String key = RedisContstants.POINTS_BOARD_KEY_PREFIX + time.format(DateTimeFormatter.ofPattern("yyyyMM"));
        // 3.删除
        redisTemplate.unlink(key);
    }
}

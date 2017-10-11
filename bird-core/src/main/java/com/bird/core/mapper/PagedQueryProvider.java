package com.bird.core.mapper;

import com.bird.core.service.query.FilterOperate;
import com.bird.core.service.query.FilterRule;
import com.bird.core.service.query.ListSortDirection;
import com.bird.core.service.query.PagedListQueryDTO;
import com.bird.core.utils.StringHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by liuxx on 2017/10/10.
 */
public class PagedQueryProvider {
    private static final Map<String, String> OperateMap;

    static {
        OperateMap = new HashMap<>();
        OperateMap.put(FilterOperate.EQUAL, "=");
        OperateMap.put(FilterOperate.NOTEQUAL, "!=");
        OperateMap.put(FilterOperate.LESS, "<");
        OperateMap.put(FilterOperate.LESSOREQUAL, "<=");
        OperateMap.put(FilterOperate.GREATER, ">");
        OperateMap.put(FilterOperate.GREATEROREQUAL, ">=");
    }

    public String queryPagedList(PagedQueryParam param) {
        PagedListQueryDTO query = param.getQuery();

        String sortField = "id";
        int sortDirection = ListSortDirection.DESC;//默认Id倒序
        if (!StringHelper.isNullOrWhiteSpace(query.getSortField())) {
            sortField = query.getSortField();
            sortDirection = query.getSortDirection();
        }


        //使用主键索引进行分页，提高效率
        String subSql = "select id from " + param.getFrom()
                + " where " + where(query.getFilters())
                + " order by " + sortField + " " + (sortDirection == ListSortDirection.DESC ? "desc" : "asc")
                + " limit " + (query.getPageIndex() - 1) * query.getPageSize() + ",1";

        String sql = "select " + param.getSelect()
                + " from " + param.getFrom()
                + " where " + where(query.getFilters()) + " and id >= (" + subSql + ")"
                + " order by " + sortField + " " + (sortDirection == ListSortDirection.DESC ? "desc" : "asc")
                + " limit " + query.getPageSize();
        return sql;
    }

    public String queryTotalCount(PagedQueryParam param) {
        String sql = "select count(id) from " + param.getFrom() + " where " + where(param.getQuery().getFilters());
        return sql;
    }

    private String where(List<FilterRule> rules) {
        int count = 0;
        StringBuilder sb = new StringBuilder();
        for (FilterRule rule : rules) {
            String value = rule.getValue();
            if (StringHelper.isNullOrWhiteSpace(value)) continue;

            if (!OperateMap.containsKey(rule.getOperate())) {
                rule.setOperate(FilterOperate.EQUAL);
            }
            if (count++ > 0) {
                sb.append(" and ");
            }
            sb.append(rule.getKey() + OperateMap.get(rule.getOperate()) + "'" + rule.getValue() + "'");
        }
        return sb.toString();
    }
}

package com.example.gateway.service;

import com.example.gateway.model.Dtos.PointTableParseResult;
import com.example.gateway.model.PointConfig;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 点表 Excel 解析与模板下载（Apache POI，xlsx 格式）。
 *
 * <p>模板列：名称 | 功能码 | 地址 | 类型 | 字序 | 缩放 | 单位 | 读写。
 * 表头固定在第 1 行，数据从第 2 行开始；类型支持
 * int16 / uint16 / int32 / uint32 / float32；读写列填“读”或“读写”。
 * 解析失败的行逐行返回原因，不影响其他行。</p>
 *
 * @author xujin
 * @since 0.2.0
 */
@Service
public class PointTableService {
    private static final String[] HEADERS = {"名称", "功能码", "地址", "类型", "字序", "缩放", "单位", "读写"};
    private static final int COLUMN_NAME = 0;
    private static final int COLUMN_FUNCTION = 1;
    private static final int COLUMN_ADDRESS = 2;
    private static final int COLUMN_TYPE = 3;
    private static final int COLUMN_ORDER = 4;
    private static final int COLUMN_SCALE = 5;
    private static final int COLUMN_UNIT = 6;
    private static final int COLUMN_RW = 7;
    private static final int HEADER_ROW = 0;
    private static final int MAX_POINTS = 200;

    private final DataFormatter formatter = new DataFormatter();

    /**
     * 解析上传的点表 xlsx。
     *
     * @throws IOException 文件读取失败
     * @throws IllegalArgumentException 空文件或缺少表头
     */
    public PointTableParseResult parse(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择 xlsx 文件");
        }
        List<PointConfig> points = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getRow(HEADER_ROW) == null) {
                throw new IllegalArgumentException("xlsx 中没有工作表或表头");
            }
            validateHeaders(sheet.getRow(HEADER_ROW));
            for (int rowIndex = HEADER_ROW + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                XSSFRow row = sheet.getRow(rowIndex);
                if (isEmptyRow(row)) {
                    continue;
                }
                if (points.size() >= MAX_POINTS) {
                    errors.add("最多 " + MAX_POINTS + " 个点位，多余行已忽略");
                    break;
                }
                try {
                    points.add(parseRow(row, points.size()));
                } catch (Exception e) {
                    errors.add("第 " + (rowIndex + 1) + " 行: " + e.getMessage());
                }
            }
        }
        if (points.isEmpty() && errors.isEmpty()) {
            errors.add("没有解析到数据行");
        }
        return new PointTableParseResult(points, errors);
    }

    /**
     * 生成点表模板 xlsx（含两行示例）。
     */
    public void writeTemplate(OutputStream output) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("点表");
            XSSFRow header = sheet.createRow(HEADER_ROW);
            for (int index = 0; index < HEADERS.length; index++) {
                header.createCell(index).setCellValue(HEADERS[index]);
            }
            Object[][] examples = {
                    {"温度", 3, 0, "float32", "ABCD", 1.0, "°C", "读"},
                    {"设定值", 3, 8, "uint16", "ABCD", 0.1, "", "读写"}
            };
            for (int rowIndex = 0; rowIndex < examples.length; rowIndex++) {
                XSSFRow row = sheet.createRow(rowIndex + 1);
                for (int column = 0; column < examples[rowIndex].length; column++) {
                    Object value = examples[rowIndex][column];
                    if (value instanceof Number number) {
                        row.createCell(column).setCellValue(number.doubleValue());
                    } else if (value instanceof Boolean flag) {
                        row.createCell(column).setCellValue(flag);
                    } else {
                        row.createCell(column).setCellValue(String.valueOf(value));
                    }
                }
            }
            for (int index = 0; index < HEADERS.length; index++) {
                sheet.autoSizeColumn(index);
            }
            workbook.write(output);
        }
    }

    private PointConfig parseRow(Row row, int index) {
        PointConfig point = new PointConfig();
        point.key = "p" + (index + 1);
        point.name = requiredText(row, COLUMN_NAME);
        point.function = (int) number(row, COLUMN_FUNCTION, 3);
        point.address = (int) number(row, COLUMN_ADDRESS, -1);
        point.type = requiredText(row, COLUMN_TYPE);
        point.order = optionalText(row, COLUMN_ORDER, "ABCD");
        point.scale = number(row, COLUMN_SCALE, 1);
        point.unit = optionalText(row, COLUMN_UNIT, "");
        point.writable = isWritable(optionalText(row, COLUMN_RW, "读"));
        point.normalize();
        return point;
    }

    private void validateHeaders(Row header) {
        String name = optionalText(header, COLUMN_NAME, "");
        String address = optionalText(header, COLUMN_ADDRESS, "");
        String type = optionalText(header, COLUMN_TYPE, "");
        if (!name.contains("名称") || !address.contains("地址") || !type.contains("类型")) {
            throw new IllegalArgumentException("表头不符合模板：需要 名称/功能码/地址/类型/字序/缩放/单位/读写");
        }
    }

    private boolean isEmptyRow(Row row) {
        if (row == null) {
            return true;
        }
        for (int index = 0; index < HEADERS.length; index++) {
            if (!formatter.formatCellValue(row.getCell(index)).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String requiredText(Row row, int column) {
        String value = optionalText(row, column, "");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(HEADERS[column] + " 不能为空");
        }
        return value;
    }

    private String optionalText(Row row, int column, String defaultValue) {
        if (row == null) {
            return defaultValue;
        }
        String value = formatter.formatCellValue(row.getCell(column)).trim();
        return value.isEmpty() ? defaultValue : value;
    }

    private double number(Row row, int column, double defaultValue) {
        String text = optionalText(row, column, "");
        if (text.isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(HEADERS[column] + " 不是数字: " + text);
        }
    }

    private boolean isWritable(String text) {
        String value = text.toLowerCase();
        return value.equals("读写") || value.equals("写") || value.equals("rw") || value.equals("w");
    }
}

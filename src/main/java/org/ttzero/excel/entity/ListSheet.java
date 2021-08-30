/*
 * Copyright (c) 2017-2018, guanquan.wang@yandex.com All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ttzero.excel.entity;

import org.ttzero.excel.annotation.ExcelColumn;
import org.ttzero.excel.annotation.HeaderComment;
import org.ttzero.excel.annotation.HeaderStyle;
import org.ttzero.excel.processor.IntConversionProcessor;
import org.ttzero.excel.reader.Cell;
import org.ttzero.excel.annotation.IgnoreExport;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


import static org.ttzero.excel.util.ReflectUtil.listDeclaredFields;
import static org.ttzero.excel.util.ReflectUtil.listReadMethods;
import static org.ttzero.excel.util.StringUtil.EMPTY;
import static org.ttzero.excel.util.StringUtil.isNotEmpty;
import static org.ttzero.excel.util.StringUtil.isEmpty;

/**
 * List is the most important data source, you can pass all
 * the data at a time, or customize the worksheet to extends
 * the {@code ListSheet}, and then override the {@link #more}
 * method to achieve segmented loading of data. The {@link #more}
 * method returns NULL or an empty array to complete the current
 * worksheet write
 *
 * @see ListMapSheet
 *
 * @author guanquan.wang at 2018-01-26 14:48
 */
public class ListSheet<T> extends Sheet {
    protected List<T> data;
    protected int start, end;
    protected boolean eof;
    private int size;

    /**
     * Constructor worksheet
     */
    public ListSheet() {
        super();
    }

    /**
     * Constructor worksheet
     *
     * @param name the worksheet name
     */
    public ListSheet(String name) {
        super(name);
    }

    /**
     * Constructor worksheet
     *
     * @param name    the worksheet name
     * @param columns the header info
     */
    public ListSheet(String name, final org.ttzero.excel.entity.Column... columns) {
        super(name, columns);
    }

    /**
     * Constructor worksheet
     *
     * @param name      the worksheet name
     * @param waterMark the water mark
     * @param columns   the header info
     */
    public ListSheet(String name, WaterMark waterMark, final org.ttzero.excel.entity.Column... columns) {
        super(name, waterMark, columns);
    }

    /**
     * Constructor worksheet
     *
     * @param data the worksheet's body data
     */
    public ListSheet(List<T> data) {
        this(null, data);
    }

    /**
     * Constructor worksheet
     *
     * @param name the worksheet name
     * @param data the worksheet's body data
     */
    public ListSheet(String name, List<T> data) {
        super(name);
        setData(data);
    }

    /**
     * Constructor worksheet
     *
     * @param data    the worksheet's body data
     * @param columns the header info
     */
    public ListSheet(List<T> data, final org.ttzero.excel.entity.Column... columns) {
        this(null, data, columns);
    }

    /**
     * Constructor worksheet
     *
     * @param name    the worksheet name
     * @param data    the worksheet's body data
     * @param columns the header info
     */
    public ListSheet(String name, List<T> data, final org.ttzero.excel.entity.Column... columns) {
        this(name, data, null, columns);
    }

    /**
     * Constructor worksheet
     *
     * @param data      the worksheet's body data
     * @param waterMark the water mark
     * @param columns   the header info
     */
    public ListSheet(List<T> data, WaterMark waterMark, final org.ttzero.excel.entity.Column... columns) {
        this(null, data, waterMark, columns);
    }

    /**
     * Constructor worksheet
     *
     * @param name      the worksheet name
     * @param data      the worksheet's body data
     * @param waterMark the water mark
     * @param columns   the header info
     */
    public ListSheet(String name, List<T> data, WaterMark waterMark, final org.ttzero.excel.entity.Column... columns) {
        super(name, waterMark, columns);
        setData(data);
    }

    /**
     * Setting the worksheet data
     *
     * @param data the body data
     * @return worksheet
     */
    public ListSheet<T> setData(final List<T> data) {
        this.data = data;
        if (!headerReady && workbook != null) {
            getAndSortHeaderColumns();
        }
        // Has data and worksheet can write
        // Paging in advance
        if (data != null && sheetWriter != null) {
            paging();
        }
        return this;
    }

    /**
     * Returns the first not null object
     *
     * @return the object
     */
    protected T getFirst() {
        if (data == null || data.isEmpty()) return null;
        T first = data.get(start);
        if (first != null) return first;
        int i = start + 1;
        do {
            first = data.get(i++);
        } while (first == null);
        return first;
    }

    /**
     * Release resources
     *
     * @throws IOException if I/O error occur
     */
    @Override
    public void close() throws IOException {
        // Maybe there has more data
        if (!eof && rows >= getRowLimit()) {
            List<T> list = more();
            if (list != null && !list.isEmpty()) {
                compact();
                data.addAll(list);
                @SuppressWarnings("unchecked")
                ListSheet<T> copy = getClass().cast(clone());
                copy.start = 0;
                copy.end = list.size();
                workbook.insertSheet(id, copy);
                // Do not close current worksheet
                shouldClose = false;
            }
        }
        if (shouldClose && data != null) {
            // Some Collection not support #remove
//            data.clear();
            data = null;
        }
        super.close();
    }

    /**
     * Reset the row-block data
     */
    @Override
    protected void resetBlockData() {
        if (!eof && left() < getRowBlockSize()) {
            append();
        }

        // Find the end index of row-block
        int end = getEndIndex();
        int len = columns.length;
        try {
            for (; start < end; rows++, start++) {
                Row row = rowBlock.next();
                row.index = rows;
                Cell[] cells = row.realloc(len);
                T o = data.get(start);
                for (int i = 0; i < len; i++) {
                    // clear cells
                    Cell cell = cells[i];
                    cell.clear();

                    Object e;
                    EntryColumn column = (EntryColumn) columns[i];
                    if (column.isIgnoreValue())
                        e = null;
                    else if (column.getMethod() != null)
                        e = column.getMethod().invoke(o);
                    else if (column.getField() != null)
                        e = column.getField().get(o);
                    else e = o;

                    cellValueAndStyle.reset(rows, cell, e, columns[i]);
                }
            }
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new ExcelWriteException(e);
        }
    }

    /**
     * Call this method to get more data when the data length
     * less than the row-block size until there is no more data
     * or more than the row limit
     */
    protected void append() {
        int rbs = getRowBlockSize();
        for (; ; ) {
            List<T> list = more();
            // No more data
            if (list == null || list.isEmpty()) {
                eof = shouldClose = true;
                break;
            }
            // The first getting
            if (data == null) {
                setData(list);

                if (list.size() < rbs) continue;
                else break;
            }
            compact();
            data.addAll(list);
            start = 0;
            end = data.size();
            // Split worksheet
            if (end >= rbs) {
                paging();
                break;
            }
        }
    }

    private void compact() {
        // Copy the remaining data to a temporary array
        int size = left();
        if (start > 0 && size > 0) {
            // append and resize
            List<T> last = new ArrayList<>(size);
            last.addAll(data.subList(start, end));
            data.clear();
            data.addAll(last);
        } else if (start > 0) data.clear();
    }

    // Returns the reflect <T> type
    protected Class<?> getTClass() {
        Class<?> clazz = null;
        if (getClass().getGenericSuperclass() instanceof ParameterizedType) {
            Type type = ((ParameterizedType) getClass()
                .getGenericSuperclass()).getActualTypeArguments()[0];
            if (type instanceof Class) {
                clazz = (Class) type;
            }
        }
        if (clazz == null) {
            T o = getFirst();
            if (o == null) return null;
            clazz = o.getClass();
        }
        return clazz;
    }

    /**
     * Get the first object of the object array witch is not NULL,
     * reflect all declared fields, and then do the following steps
     * <p>
     * step 1. If the method has {@link ExcelColumn} annotation, the value of
     * this annotation is used as the column name.
     * <p>
     * step 2. If the {@link ExcelColumn} annotation has no value or empty value,
     * the field name is used as the column name.
     * <p>
     * step 3. If the field has {@link ExcelColumn} annotation, the value of
     * this annotation is used as the column name.
     * <p>
     * step 4. Skip this Field if it has a {@link IgnoreExport} annotation,
     * or the field which has not {@link ExcelColumn} annotation.
     * <p>
     * The column order is the same as the order in declared fields.
     *
     * @return the column array length
     */
    protected int init() {
        Class<?> clazz = getTClass();
        if (clazz == null) return 0;

        Map<String, Method> tmp = new HashMap<>();
        try {
            PropertyDescriptor[] propertyDescriptors = Introspector.getBeanInfo(clazz, Object.class)
                    .getPropertyDescriptors();
            for (PropertyDescriptor pd : propertyDescriptors) {
                Method method = pd.getReadMethod();
                if (method != null) tmp.put(pd.getName(), method);
            }
        } catch (IntrospectionException e) {
            LOGGER.warn("Get class {} methods failed.", clazz);
        }

        Field[] declaredFields = listDeclaredFields(clazz, c -> !ignoreColumn(c));

        boolean forceExport = this.forceExport == 1;

        if (!hasHeaderColumns()) {
            // Get ExcelColumn annotation method
            List<org.ttzero.excel.entity.Column> list = new ArrayList<>(declaredFields.length);

            for (int i = 0; i < declaredFields.length; i++) {
                Field field = declaredFields[i];
                field.setAccessible(true);
                String gs = field.getName();

                // Ignore annotation on read method
                Method method = tmp.get(gs);
                if (method != null) {
                    // Filter all ignore column
                    if (ignoreColumn(method)) {
                        declaredFields[i] = null;
                        continue;
                    }

                    EntryColumn column = createColumn(method);
                    // Force export
                    if (column == null && forceExport) {
                        column = new EntryColumn(gs, EMPTY, false);
                    }
                    if (column != null) {
                        column.method = method;
                        column.field = field;
                        column.clazz = method.getReturnType();
                        column.key = gs;
                        if (isEmpty(column.name)) {
                            column.name = gs;
                        }
                        list.add(column);

                        // Attach header style
                        buildHeaderStyle(method, field, column);
                        // Attach header comment
                        buildHeaderComment(method, field, column);
                        continue;
                    }
                }

                EntryColumn column = createColumn(field);
                // Force export
                if (column == null && forceExport) {
                    column = new EntryColumn(gs, EMPTY, false);
                }
                if (column != null) {
                    list.add(column);
                    column.field = field;
                    column.key = gs;
                    if (isEmpty(column.name)) {
                        column.name = gs;
                    }
                    if (method != null) {
                        column.clazz = method.getReturnType();
                        column.method = method;
                    } else column.clazz = field.getType();

                    // Attach header style
                    buildHeaderStyle(method, field, column);
                    // Attach header comment
                    buildHeaderComment(method, field, column);
                }
            }

            // Attach some custom column
            List<org.ttzero.excel.entity.Column> attachList = attachOtherColumn(tmp, clazz);
            if (attachList != null) list.addAll(attachList);

            // No column to write
            if (list.isEmpty()) {
                headerReady = eof = shouldClose = true;
                this.end = 0;
                LOGGER.warn("Class [{}] do not contains properties to export.", clazz);
                return 0;
            }
            columns = new org.ttzero.excel.entity.Column[list.size()];
            list.toArray(columns);
        } else {
            for (int i = 0; i < columns.length; i++) {
                org.ttzero.excel.entity.Column hc = columns[i];
                if (!(hc instanceof EntryColumn)) {
                    hc = new EntryColumn(hc);
                    columns[i] = hc;
                }
                EntryColumn ec = (EntryColumn) hc;
                Method method = tmp.get(hc.key);
                if (method != null) {
                    method.setAccessible(true);
                }
                ec.method = method;

                for (Field field : declaredFields) {
                    if (field.getName().equals(hc.key)) {
                        field.setAccessible(true);
                        ec.field = field;
                        break;
                    }
                }

                if (method == null && ec.field == null) {
                    LOGGER.warn("Column [" + hc.getName() + "(" + hc.key + ")"
                            + "] not declare in class " + clazz);
                    hc.ignoreValue();
                } else if (hc.getClazz() == null) {
                    hc.setClazz(ec.method != null ? ec.method.getReturnType() : ec.field.getType());
                }

                // Attach header style
                if (hc.getHeaderStyleIndex() == -1) {
                    buildHeaderStyle(method, ec.field, hc);
                }
                // Attach header comment
                if (hc.headerComment == null) {
                    buildHeaderComment(method, ec.field, hc);
                }
            }
        }

        // Merge Header Style defined on Entry Class
        mergeGlobalSetting(clazz);

        return columns.length;
    }

    /**
     * Create column from {@link ExcelColumn} annotation
     * <p>
     * Override the method to extend custom comments
     *
     * @param ao {@link AccessibleObject} witch defined the {@code ExcelColumn} annotation
     * @return the Worksheet's {@link Column} information
     */
    protected EntryColumn createColumn(AccessibleObject ao) {
        // Filter all ignore column
        if (ignoreColumn(ao)) return null;

        ao.setAccessible(true);
        ExcelColumn ec = ao.getAnnotation(ExcelColumn.class);
        if (ec != null) {
            EntryColumn column = new EntryColumn(ec.value(), EMPTY, ec.share());
            column.styles = workbook.getStyles();
            // Number format
            if (isNotEmpty(ec.format())) {
                column.setNumFmt(ec.format());
            }
            // Wrap
            column.setWrapText(ec.wrapText());
            // Column index
            if (ec.colIndex() > -1) {
                column.colIndex = ec.colIndex();
            }
            return column;
        }
        return null;
    }

    /**
     * Build header style
     *
     * @param main the getter method
     * @param sub the defined field
     * @param column the header column
     */
    protected void buildHeaderStyle(AccessibleObject main, AccessibleObject sub, org.ttzero.excel.entity.Column column) {
        HeaderStyle hs = null;
        if (main != null) {
            hs = main.getAnnotation(HeaderStyle.class);
        }
        if (hs == null && sub != null) {
            hs = sub.getAnnotation(HeaderStyle.class);
        }
        if (hs != null) {
            column.setHeaderStyle(this.buildHeadStyle(hs.fontColor(), hs.fillFgColor()));
        }
    }

    /**
     * Build header comment
     *
     * @param main the getter method
     * @param sub the defined field
     * @param column the header column
     */
    protected void buildHeaderComment(AccessibleObject main, AccessibleObject sub, org.ttzero.excel.entity.Column column) {
        HeaderComment comment = null;
        if (main != null) {
            comment = main.getAnnotation(HeaderComment.class);
            if (comment == null) {
                ExcelColumn ec = main.getAnnotation(ExcelColumn.class);
                if (ec != null) comment = ec.comment();
            }
        }
        if (comment == null && sub != null) {
            comment = sub.getAnnotation(HeaderComment.class);
            if (comment == null) {
                ExcelColumn ec = sub.getAnnotation(ExcelColumn.class);
                if (ec != null) comment = ec.comment();
            }
        }
        if (comment != null && (isNotEmpty(comment.value()) || isNotEmpty(comment.title()))) {
            column.headerComment = new Comment(comment.title(), comment.value());
        }
    }

    /**
     * Merge Header Style defined on Entry Class
     *
     * @param clazz  Class of &lt;T&gt;
     */
    protected void mergeGlobalSetting(Class<?> clazz) {
        HeaderStyle headerStyle = clazz.getDeclaredAnnotation(HeaderStyle.class);
        int style = 0;
        if (headerStyle != null) {
            style = buildHeadStyle(headerStyle.fontColor(), headerStyle.fillFgColor());
        }
        for (org.ttzero.excel.entity.Column column : columns) {
            if (style > 0 && column.getHeaderStyleIndex() == -1)
                column.setHeaderStyle(style);
        }
    }

    /**
     * Ignore some columns, override this method to add custom filtering
     *
     * @param ao {@code Method} or {@code Field}
     * @return true if ignore current column
     */
    protected boolean ignoreColumn(AccessibleObject ao) {
        return ao.getAnnotation(IgnoreExport.class) != null;
    }

    /**
     * Attach some custom columns
     *
     * @param existsMethodMapper all exists method collection by default
     * @param clazz Class of &lt;T&gt;
     * @return list of {@link org.ttzero.excel.entity.Column} or null if no more columns to attach
     */
    protected List<org.ttzero.excel.entity.Column> attachOtherColumn(Map<String, Method> existsMethodMapper, Class<?> clazz) {
        // Collect the method which has ExcelColumn annotation
        Method[] readMethods = null;
        try {
            Collection<Method> values = existsMethodMapper.values();
            readMethods = listReadMethods(clazz, method -> method.getAnnotation(ExcelColumn.class) != null
                    && method.getAnnotation(IgnoreExport.class) == null && !values.contains(method));
        } catch (IntrospectionException e) {
            // Ignore
        }

        if (readMethods != null) {
            Set<Method> existsMethods = new HashSet<>(existsMethodMapper.values());
            List<org.ttzero.excel.entity.Column> list = new ArrayList<>();
            for (Method method : readMethods) {
                // Exclusions exists
                if (existsMethods.contains(method)) continue;
                EntryColumn column = createColumn(method);
                if (column != null) {
                    list.add(column);
                    column.method = method;
                    column.clazz = method.getReturnType();
                    column.key = method.getName();
                    if (isEmpty(column.name)) {
                        column.name = method.getName();
                    }

                    // Attach header style
                    buildHeaderStyle(method, null, column);
                    // Attach header comment
                    buildHeaderComment(method, null, column);
                }
            }
            return list;
        }
        return null; // No more columns
    }

    /**
     * Returns the header column info
     *
     * @return array of column
     */
    @Override
    public org.ttzero.excel.entity.Column[] getHeaderColumns() {
        if (!headerReady) {
            // create header columns
            int size = init();
            if (size <= 0) {
                columns = new org.ttzero.excel.entity.Column[0];
            } else {
                headerReady = true;
            }
        }
        return columns;
    }

    /**
     * Returns the end index of row-block
     *
     * @return the end index
     */
    protected int getEndIndex() {
        int blockSize = getRowBlockSize(), rowLimit = getRowLimit();
        if (rows + blockSize > rowLimit) {
            blockSize = rowLimit - rows;
        }
        int end = start + blockSize;
        return Math.min(end, this.end);
    }

    /**
     * Returns total rows in this worksheet
     *
     * @return -1 if unknown or uncertain
     */
    @Override
    public int size() {
        return !shouldClose ? size : -1;
    }

    /**
     * Returns left data in array to be write
     *
     * @return the left number
     */
    protected int left() {
        return end - start;
    }

    /**
     * Split worksheet data
     */
    @Override
    protected void paging() {
        int len = dataSize(), limit = getRowLimit();
        // paging
        if (len + rows > limit) {
            // Reset current index
            end = limit - rows + start;
            shouldClose = false;
            eof = true;
            size = limit;

            int n = id;
            for (int i = end; i < len; ) {
                @SuppressWarnings("unchecked")
                ListSheet<T> copy = getClass().cast(clone());
                copy.start = i;
                copy.end = (i = Math.min(i + limit, len));
                copy.size = copy.end - copy.start;
                copy.eof = copy.size == limit;
                workbook.insertSheet(n++, copy);
            }
            // Close on the last copy worksheet
            workbook.getSheetAt(n - 1).shouldClose = true;
        } else {
            end = len;
            size += len;
        }
    }

    /**
     * Returns total data size before split
     *
     * @return the total size
     */
    public int dataSize() {
        return data != null ? data.size() : 0;
    }

    /**
     * This method is used for the worksheet to get the data.
     * This is a data source independent method. You can get data
     * from any data source. Since this method is stateless, you
     * should manage paging or other information in your custom Sheet.
     * <p>
     * The more data you get each time, the faster write speed. You
     * should minimize the database query or network request, but the
     * excessive data will put pressure on the memory. Please balance
     * this value between the speed and memory. You can refer to {@code 2^8 ~ 2^10}
     * <p>
     * This method is blocked
     *
     * @return The data output to the worksheet, if a null or empty
     * ArrayList returned, mean that the current worksheet is finished written.
     */
    protected List<T> more() {
        return null;
    }

    public static class EntryColumn extends org.ttzero.excel.entity.Column {
        Method method;
        Field field;

        public EntryColumn() { }
        public EntryColumn(String name) {
            this.name = name;
        }
        public EntryColumn(String name, Class<?> clazz) {
            super(name, clazz);
        }

        public EntryColumn(String name, String key) {
            super(name, key);
        }

        public EntryColumn(String name, String key, Class<?> clazz) {
            super(name, key, clazz);
        }

        public EntryColumn(String name, Class<?> clazz, IntConversionProcessor processor) {
            super(name, clazz, processor);
        }

        public EntryColumn(String name, String key, IntConversionProcessor processor) {
            super(name, key, processor);
        }

        public EntryColumn(String name, Class<?> clazz, boolean share) {
            super(name, clazz, share);
        }

        public EntryColumn(String name, String key, boolean share) {
            super(name, key, share);
        }

        public EntryColumn(String name, Class<?> clazz, IntConversionProcessor processor, boolean share) {
            super(name, clazz, processor, share);
        }

        public EntryColumn(String name, String key, Class<?> clazz, IntConversionProcessor processor) {
            super(name, key, clazz, processor);
        }

        public EntryColumn(String name, String key, IntConversionProcessor processor, boolean share) {
            super(name, key, processor, share);
        }

        public EntryColumn(String name, Class<?> clazz, int cellStyle) {
            super(name, clazz, cellStyle);
        }

        public EntryColumn(String name, String key, int cellStyle) {
            super(name, key, cellStyle);
        }

        public EntryColumn(String name, Class<?> clazz, int cellStyle, boolean share) {
            super(name, clazz, cellStyle, share);
        }

        public EntryColumn(String name, String key, int cellStyle, boolean share) {
            super(name, key, cellStyle, share);
        }

        public EntryColumn(org.ttzero.excel.entity.Column other) {
            this.key = other.key;
            this.name = other.name;
            this.clazz = other.clazz;
            this.share = other.share;
            this.processor = other.processor;
            this.styleProcessor = other.styleProcessor;
            this.width = other.width;
            this.o = other.o;
            this.styles = other.styles;
            this.headerComment = other.headerComment;
            this.numFmt = other.numFmt;
            this.ignoreValue = other.ignoreValue;
            this.wrapText = other.wrapText;
            this.colIndex = other.colIndex;
            if (other.cellStyle > 0) setCellStyle(other.cellStyle);
            if (other.headerStyle > 0) setHeaderStyle(other.headerStyle);
        }

        public Method getMethod() {
            return method;
        }

        public Field getField() {
            return field;
        }
    }
}

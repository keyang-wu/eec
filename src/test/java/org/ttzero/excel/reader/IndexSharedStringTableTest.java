/*
 * Copyright (c) 2017-2019, guanquan.wang@yandex.com All Rights Reserved.
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

package org.ttzero.excel.reader;

import org.junit.Test;

import java.io.IOException;

import static org.ttzero.excel.Print.println;
import static org.ttzero.excel.entity.WorkbookTest.getRandomString;

/**
 * @author guanquan.wang at 2019-05-10 21:42
 */
public class IndexSharedStringTableTest {

    @Test public void test1() throws IOException {
        try (IndexSharedStringTable sst = new IndexSharedStringTable()) {
            sst.push('a');
            sst.push('b');

            String value;
            value = sst.get(0);
            println(value);
            assert value.equals("a");

            value = sst.get(1);
            println(value);
            assert value.equals("b");

            String[] array = new String[2];
            int n = sst.get(0, array);
            for (int i = 0; i < n; i++) {
                println(array[i]);
            }
        }
    }

    @Test public void test2() throws IOException {
        try (IndexSharedStringTable sst = new IndexSharedStringTable()) {
            int length = 10000;
            String[] buf = new String[length];
            for (int i = 0; i < length; i++)
                buf[i] = getRandomString();

            for (String s : buf) {
                sst.push(s);
            }

            for (int i = 0; i < length; i++) {
                String s = sst.get(i);
                assert s.equals(buf[i]);
            }

            int fromIndex = 0, size = length;
            String[] _buf = new String[size];
            size = sst.get(fromIndex, _buf);
            assert size == length - fromIndex;
            for (int i = 0; i < size; i++) {
                assert _buf[i].equals(buf[fromIndex + i]);
            }
        }
    }

    @Test public void test3() throws IOException {
        try (IndexSharedStringTable sst = new IndexSharedStringTable()) {
            long start = System.currentTimeMillis();
            for (int i = 0; i < 1_000_000; i++) {
                sst.push(getRandomString());
            }
            println("Write SST cost: " + (System.currentTimeMillis() - start));

            start = System.currentTimeMillis();

            for (int i = 0; i < 1_000_000; i++) {
                sst.get(i);
            }
            println("Read SST cost: " + (System.currentTimeMillis() - start));
        }
    }

}

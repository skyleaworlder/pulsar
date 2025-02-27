/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.loadbalance;

import static org.testng.Assert.assertEquals;

import java.util.Map;
import java.util.Optional;


import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.loadbalance.impl.LeastLongTermMessageRate;
import org.apache.pulsar.broker.loadbalance.impl.LeastResourceUsageWithWeight;
import org.apache.pulsar.policies.data.loadbalancer.LocalBrokerData;
import org.apache.pulsar.policies.data.loadbalancer.ResourceUsage;
import org.apache.pulsar.policies.data.loadbalancer.BrokerData;
import org.apache.pulsar.policies.data.loadbalancer.BundleData;
import org.apache.pulsar.policies.data.loadbalancer.TimeAverageBrokerData;
import org.testng.annotations.Test;

@Test(groups = "broker")
public class ModularLoadManagerStrategyTest {

    // Test that least long term message rate works correctly.
    public void testLeastLongTermMessageRate() {
        BundleData bundleData = new BundleData();
        BrokerData brokerData1 = initBrokerData();
        BrokerData brokerData2 = initBrokerData();
        BrokerData brokerData3 = initBrokerData();
        brokerData1.getTimeAverageData().setLongTermMsgRateIn(100);
        brokerData2.getTimeAverageData().setLongTermMsgRateIn(200);
        brokerData3.getTimeAverageData().setLongTermMsgRateIn(300);
        LoadData loadData = new LoadData();
        Map<String, BrokerData> brokerDataMap = loadData.getBrokerData();
        brokerDataMap.put("1", brokerData1);
        brokerDataMap.put("2", brokerData2);
        brokerDataMap.put("3", brokerData3);
        ServiceConfiguration conf = new ServiceConfiguration();
        ModularLoadManagerStrategy strategy = new LeastLongTermMessageRate();
        assertEquals(strategy.selectBroker(brokerDataMap.keySet(), bundleData, loadData, conf), Optional.of("1"));
        brokerData1.getTimeAverageData().setLongTermMsgRateIn(400);
        assertEquals(strategy.selectBroker(brokerDataMap.keySet(), bundleData, loadData, conf), Optional.of("2"));
        brokerData2.getLocalData().setCpu(new ResourceUsage(90, 100));
        assertEquals(strategy.selectBroker(brokerDataMap.keySet(), bundleData, loadData, conf), Optional.of("3"));
    }

    // Test that least resource usage with weight works correctly.
    public void testLeastResourceUsageWithWeight() {
        BundleData bundleData = new BundleData();
        BrokerData brokerData1 = initBrokerData(10, 100);
        BrokerData brokerData2 = initBrokerData(30, 100);
        BrokerData brokerData3 = initBrokerData(60, 100);
        LoadData loadData = new LoadData();
        Map<String, BrokerData> brokerDataMap = loadData.getBrokerData();
        brokerDataMap.put("1", brokerData1);
        brokerDataMap.put("2", brokerData2);
        brokerDataMap.put("3", brokerData3);
        ServiceConfiguration conf = new ServiceConfiguration();
        conf.setLoadBalancerCPUResourceWeight(1.0);
        conf.setLoadBalancerMemoryResourceWeight(0.1);
        conf.setLoadBalancerDirectMemoryResourceWeight(0.1);
        conf.setLoadBalancerBandwithInResourceWeight(1.0);
        conf.setLoadBalancerBandwithOutResourceWeight(1.0);
        conf.setLoadBalancerHistoryResourcePercentage(0.5);
        conf.setLoadBalancerAverageResourceUsageDifferenceThresholdPercentage(5);

        ModularLoadManagerStrategy strategy = new LeastResourceUsageWithWeight();
        assertEquals(strategy.selectBroker(brokerDataMap.keySet(), bundleData, loadData, conf), Optional.of("1"));

        brokerData1 = initBrokerData(20,100);
        brokerData2 = initBrokerData(30,100);
        brokerData3 = initBrokerData(50,100);
        brokerDataMap.put("1", brokerData1);
        brokerDataMap.put("2", brokerData2);
        brokerDataMap.put("3", brokerData3);
        assertEquals(strategy.selectBroker(brokerDataMap.keySet(), bundleData, loadData, conf), Optional.of("1"));

        brokerData1 = initBrokerData(30,100);
        brokerData2 = initBrokerData(30,100);
        brokerData3 = initBrokerData(40,100);
        brokerDataMap.put("1", brokerData1);
        brokerDataMap.put("2", brokerData2);
        brokerDataMap.put("3", brokerData3);
        assertEquals(strategy.selectBroker(brokerDataMap.keySet(), bundleData, loadData, conf), Optional.of("1"));

        brokerData1 = initBrokerData(30,100);
        brokerData2 = initBrokerData(30,100);
        brokerData3 = initBrokerData(40,100);
        brokerDataMap.put("1", brokerData1);
        brokerDataMap.put("2", brokerData2);
        brokerDataMap.put("3", brokerData3);
        assertEquals(strategy.selectBroker(brokerDataMap.keySet(), bundleData, loadData, conf), Optional.of("1"));

        brokerData1 = initBrokerData(35,100);
        brokerData2 = initBrokerData(20,100);
        brokerData3 = initBrokerData(45,100);
        brokerDataMap.put("1", brokerData1);
        brokerDataMap.put("2", brokerData2);
        brokerDataMap.put("3", brokerData3);
        assertEquals(strategy.selectBroker(brokerDataMap.keySet(), bundleData, loadData, conf), Optional.of("2"));
    }

    private BrokerData initBrokerData(double usage, double limit) {
        LocalBrokerData localBrokerData = new LocalBrokerData();
        localBrokerData.setCpu(new ResourceUsage(usage, limit));
        localBrokerData.setMemory(new ResourceUsage(usage, limit));
        localBrokerData.setDirectMemory(new ResourceUsage(usage, limit));
        localBrokerData.setBandwidthIn(new ResourceUsage(usage, limit));
        localBrokerData.setBandwidthOut(new ResourceUsage(usage, limit));
        BrokerData brokerData = new BrokerData(localBrokerData);
        TimeAverageBrokerData timeAverageBrokerData = new TimeAverageBrokerData();
        brokerData.setTimeAverageData(timeAverageBrokerData);
        return brokerData;
    }

    private BrokerData initBrokerData() {
        LocalBrokerData localBrokerData = new LocalBrokerData();
        localBrokerData.setCpu(new ResourceUsage());
        localBrokerData.setMemory(new ResourceUsage());
        localBrokerData.setBandwidthIn(new ResourceUsage());
        localBrokerData.setBandwidthOut(new ResourceUsage());
        BrokerData brokerData = new BrokerData(localBrokerData);
        TimeAverageBrokerData timeAverageBrokerData = new TimeAverageBrokerData();
        brokerData.setTimeAverageData(timeAverageBrokerData);
        return brokerData;
    }
}

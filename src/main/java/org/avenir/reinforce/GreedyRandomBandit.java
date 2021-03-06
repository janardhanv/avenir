/*
 * avenir: Predictive analytic based on Hadoop Map Reduce
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.avenir.reinforce;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.chombo.util.DynamicBean;
import org.chombo.util.Utility;

/**
 * Implements greedy multiarm bandit  reinforcement learning algorithms
 * @author pranab
 *
 */
public class GreedyRandomBandit   extends Configured implements Tool {

	@Override
	public int run(String[] args) throws Exception {
        Job job = new Job(getConf());
        String jobName = "Greedy random bandit problem";
        job.setJobName(jobName);
        
        job.setJarByClass(GreedyRandomBandit.class);

        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        Utility.setConfiguration(job.getConfiguration(), "avenir");
        job.setMapperClass(GreedyRandomBandit.BanditMapper.class);
        
        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);

        int status =  job.waitForCompletion(true) ? 0 : 1;
        return status;
	}
	
	/**
	 * @author pranab
	 *
	 */
	public static class BanditMapper extends Mapper<LongWritable, Text, NullWritable, Text> {
		private String fieldDelimRegex;
    	private String fieldDelim ;
		private String[] items;
		private Text outVal  = new Text();
		private int roundNum;
		private float randomSelectionProb;
		private String  probRedAlgorithm;
		private String curGroupID = null;
		private String groupID;
		private int countOrdinal;
		private int rewardOrdinal;
		private static final String PROB_RED_LINEAR = "linear";
		private static final String PROB_RED_LOG_LINEAR = "logLinear";
		private float probReductionConstant;
		private static final String AUER_GREEDY = "AuerGreedy";
		private static final String ITEM_ID = "itemID";
		private static final String ITEM_COUNT = "count";
		private static final String ITEM_REWARD = "reward";
		private Map<String, Integer> groupBatchCount = new HashMap<String, Integer>();
		private int auerGreedyConstant;
		private GroupedItems groupedItems = new GroupedItems();
		
		
		/* (non-Javadoc)
		 * @see org.apache.hadoop.mapreduce.Mapper#setup(org.apache.hadoop.mapreduce.Mapper.Context)
		 */
		protected void setup(Context context) throws IOException, InterruptedException {
        	Configuration conf = context.getConfiguration();
        	fieldDelimRegex = conf.get("field.delim.regex", ",");
        	fieldDelim = conf.get("field.delim", ",");

        	roundNum = conf.getInt("current.round.num",  -1);
        	randomSelectionProb = conf.getFloat("random.selection.prob", (float)0.5);
        	probRedAlgorithm = conf.get("prob.reduction.algorithm", PROB_RED_LINEAR );
        	probReductionConstant = conf.getFloat("prob.reduction.constant",  (float)1.0);
        	countOrdinal = conf.getInt("count.ordinal",  -1);
        	rewardOrdinal = conf.getInt("reward.ordinal",  -1);
        	auerGreedyConstant = conf.getInt("auer.greedy.contant", 5);
 
        	//batch size
        	List<String[]> lines = Utility.parseFileLines(conf,  "group.item.count.path",  ",");
        	String groupID;
 			int batchSize;
        	for (String[] line : lines) {
        		groupID= line[0];
        		batchSize = Integer.parseInt(line[1]);
        		groupBatchCount.put(groupID,   batchSize );
        	}
        	
        }

        /* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Mapper#cleanup(org.apache.hadoop.mapreduce.Mapper.Context)
         */
        protected void cleanup(Context context)  throws IOException, InterruptedException {
			select( context);
        }
        
        @Override
        protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
            items  =  value.toString().split(fieldDelimRegex);
            groupID = items[0];
    		if (null == curGroupID || !groupID.equals(curGroupID)) {
    			//new group
    			if (null == curGroupID) {
    				collectGroupItems();
    			} else  {
    				select( context);
    			}
    			
    			groupedItems.initialize();
    			curGroupID = groupID;
    		} else {
    			//existing group
				collectGroupItems();
    		}
        }

        /**
         * @return
         */
        private int getBatchSize() {
        	int batchSize = groupBatchCount.isEmpty() ? 1 : groupBatchCount.get(curGroupID);
        	return batchSize;
        }
        
        /**
         * 
         */
        private void collectGroupItems() {
        	groupedItems.createtem(items[1], Integer.parseInt(items[countOrdinal]), Integer.parseInt(items[rewardOrdinal]));
        }
        
        /**
         * @return
         * @throws InterruptedException 
         * @throws IOException 
         */
        private void select(Context context) throws IOException, InterruptedException {
			if (probRedAlgorithm.equals(PROB_RED_LINEAR )) {
				 linearSelect(context, false);
			} else if (probRedAlgorithm.equals(PROB_RED_LOG_LINEAR )) {
				 linearSelect(context, true);
			}  else if (probRedAlgorithm.equals(AUER_GREEDY )) {
				 greedyAuerSelect(context);
			}
        }        
        
        /**
         * @return
         * @throws InterruptedException 
         * @throws IOException 
         */
        private void linearSelect(Context context, boolean logLinear) throws IOException, InterruptedException {
        	Set<String> items = new HashSet<String>();
        	int batchSize = getBatchSize();
        	int count = (roundNum -1) * batchSize;
        	String itemID = null;
        	float curProb;
        	
        	//select items for the batch
        	for (int i = 0; i < batchSize; ++i) {
        		++count;
        		if (logLinear) {
        			curProb = (float)(randomSelectionProb * probReductionConstant * Math.log(count) / count);
        		} else {
        			curProb = randomSelectionProb * probReductionConstant / count ;
        		}
        		curProb = curProb <= randomSelectionProb ? curProb : randomSelectionProb;
            	itemID = linearSelectHelper(curProb, context);
            	while(items.contains(itemID)) {
            		itemID = linearSelectHelper(curProb, context);
            	}
            	items.add(itemID);
        	}
        	
        	//emit all selected items
          	for (String item : items) {
    			outVal.set(curGroupID + fieldDelim + item);
        		context.write(NullWritable.get(), outVal);
          	}
        }
        

        /**
         * @param context
         * @throws IOException
         * @throws InterruptedException
         */
        private void greedyAuerSelect(Context context) throws IOException, InterruptedException {
        	List<String> items = new ArrayList<String>();
        	int batchSize = getBatchSize();
        	int count = (roundNum -1) * batchSize;
    		int maxReward = 0;
 
    		//max reward in this group
    		DynamicBean maxRewardItem = groupedItems.getMaxRewardItem();
    		maxReward = maxRewardItem.getInt(ITEM_REWARD);
    		int groupCount = groupedItems.size();
    		
    		//collect items not tried before
    		List<DynamicBean> collectedItems = groupedItems.collectItemsNotTried(batchSize);
    		count += collectedItems.size();
    		for (DynamicBean it : collectedItems) {
    			items.add(it.getString(ITEM_ID));
    		}
    		
    		//collect items according to greedy algorithm 
    		if (items.size() < batchSize) {
    			groupedItems.remove(maxRewardItem);
    			int nextMaxreward = groupedItems.getMaxRewardItem().getInt(ITEM_REWARD);
    			double rewardDiff = (double)((maxReward - nextMaxreward)) / maxReward;
    			groupedItems.add(maxRewardItem);
    			
    			//select as per Auer greedy algorithm
    			while (items.size() < batchSize) {
    				double prob = auerGreedyConstant * groupCount / (rewardDiff * rewardDiff * count);
    	   			prob = prob > 1.0 ? 1.0 : prob;
    	   		    DynamicBean selectedItem = null;
	            	if (prob < Math.random()) {
	            		//select random
	            		selectedItem = groupedItems.selectRandom();
	            	} else {
	            		//select one with best reward
	            		selectedItem = groupedItems.getMaxRewardItem();
	            	}    	
	            	items.add(selectedItem.getString(ITEM_ID));
	            	groupedItems.remove(selectedItem);
	            	++count;
    			}
    		}        
        }        
        
        /**
         * @param curProb
         * @param context
         * @throws IOException
         * @throws InterruptedException
         */
        private String linearSelectHelper(float curProb, Context context) throws IOException, InterruptedException {
        	String itemID = null;
        	if (curProb < Math.random()) {
        		//select random
        		itemID = groupedItems.selectRandom().getString(ITEM_ID);
        	} else {
        		//choose best so far
        		DynamicBean maxRewardItem = groupedItems.getMaxRewardItem();
        		
        		if (null == maxRewardItem) {
            		//nothing tried, choose randomly
            		itemID =  groupedItems.selectRandom().getString(ITEM_ID);
        		} else {
        			itemID = maxRewardItem.getString(ITEM_ID);
        		}
        	}
    		return itemID;
        }

	}
	
	
    /**
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new GreedyRandomBandit(), args);
        System.exit(exitCode);
    }
	
}

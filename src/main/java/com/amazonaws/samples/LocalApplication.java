package com.amazonaws.samples;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;


import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.StartInstancesRequest;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.util.Base64;


public class LocalApplication {
	private static AWSCredentialsProvider credentialsProvider = new AWSStaticCredentialsProvider(new ProfileCredentialsProvider().getCredentials());
	private static AmazonEC2 ec2;
	private static AmazonS3 s3;
	private static AmazonSQS sqs;
	private static String bucketName = credentialsProvider.getCredentials().getAWSAccessKeyId().toLowerCase();
	private static String mySendQueueUrlName = "local_send_manager_queue";
	private static String myReceiveQueueUrlName = "local_receive_manager_queue";
	private static String mySendQueueUrl, myReceiveQueueUrl;
	private static Map<String,String> input_output_files;


	public static void main(String[] args) {
		long startTime = System.currentTimeMillis();
		boolean terminate = false;
		System.out.println("bucketName: " + bucketName);
		if(args[args.length - 1].equals("terminate")) {
			terminate = true;
		}

		//for storage
		s3 = AmazonS3ClientBuilder.standard()
				.withCredentials(credentialsProvider)
				.withRegion("us-east-1")
				.build();
		//queue
		sqs = AmazonSQSClientBuilder.standard()
				.withCredentials(credentialsProvider)
				.withRegion("us-east-1")
				.build();

		System.out.println("before creating manager instance.\n");
		Instance managerInstance = createManagerInstance(Integer.parseInt(args[args.length - 1]));
		mySendQueueUrl = getQueue(mySendQueueUrlName);
		myReceiveQueueUrl = getQueue(myReceiveQueueUrlName);
		System.out.println("Before createS3.\n");
		createS3();
		uploadFiles(s3 , args);
		System.out.println("Sending a message to Local-Manager Queue.\n");

		        
		/* The application will send a message to a specified
		 *  SQS queue, stating the location of the images list on S3
	    */
		sqs.sendMessage(new SendMessageRequest(mySendQueueUrl, "New file uploaded##" + bucketName + "##" + args[0] + "##" + args[args.length - 2 + 1]));
		//input_output_files.put(args[0], args[(args.length - 1)/2 ]);

		ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(myReceiveQueueUrl);
		/*
		 * ****checks all messages from SQS****
		 * The application will check a specified SQS queue
		 *  for a message indicating the process is done 
		 *  and the response is available on S3.
		 */
		for(Message message : sqs.receiveMessage(receiveMessageRequest).getMessages()) {
			if(message == null)
				continue;
			else if(message.getBody().startsWith("###")) { //debug
				System.out.println(message.getBody());
				String messageRecieptHandle = message.getReceiptHandle();
				sqs.deleteMessage(new DeleteMessageRequest(myReceiveQueueUrl, messageRecieptHandle));
			}
			else {
				/*
				 * The application will download the response from S3.
				 */
				S3Object object;
				try {
					object = s3.getObject(new GetObjectRequest(bucketName, message.getBody()));
				}
				catch (Exception e) {
					continue;
				}

				//TODO: add HTML export

				String messageRecieptHandle = message.getReceiptHandle();
				sqs.deleteMessage(new DeleteMessageRequest(myReceiveQueueUrl, messageRecieptHandle));

				s3.deleteObject(bucketName, message.getBody());
			}
		}


		if(terminate) {
			sqs.sendMessage(new SendMessageRequest(mySendQueueUrl,"$$terminate"));
			boolean areDeadWorkers = false;
			while (!areDeadWorkers) {
				ReceiveMessageRequest receiveMessageRequest1 = new ReceiveMessageRequest(myReceiveQueueUrl);
				for(Message message : sqs.receiveMessage(receiveMessageRequest1).getMessages()) {
					if(message.getBody().equals("$$WorkersTerminated")) {
						List<String> instances = new ArrayList<String>();
						instances.add(managerInstance.getInstanceId());
						TerminateInstancesRequest req = new TerminateInstancesRequest(instances);
						ec2.terminateInstances(req);
						String messageRecieptHandle = message.getReceiptHandle();
						sqs.deleteMessage(new DeleteMessageRequest(myReceiveQueueUrl, messageRecieptHandle));
						areDeadWorkers = true;
					}
				}
			}
		}

		long endTime   = System.currentTimeMillis();
		long totalTime = endTime - startTime;
		System.out.println(totalTime);
		System.out.println("DONE");

	}
	private static Instance createManagerInstance(int workerCounter) {
		//Creates a new Amazon EC2 instance
		ec2 = AmazonEC2ClientBuilder.standard()
				.withCredentials(credentialsProvider)
				.withRegion("us-east-1")
				.build();
		//get list of instances from aws
		DescribeInstancesRequest request1 = new DescribeInstancesRequest();
		DescribeInstancesResult response = ec2.describeInstances(request1);
		for(Reservation res : response.getReservations()) {
			List<Instance> instances = res.getInstances();
			for(Instance in: instances) {
				for (Tag tag: in.getTags()) { 
					if(tag.getKey().equals("Manager")) {
						if(!(in.getState().getName().equals("running"))) {// starts manager instance
							if(in.getState().getName().equals("terminated") || in.getState().getName().equals("shutting-down"))
								continue;
							List<String> ins = new ArrayList<String>();
							ins.add(in.getInstanceId());
							StartInstancesRequest req = new StartInstancesRequest(ins);
							ec2.startInstances(req);
						}
						return in;
					}
				}
			}
		}

		Instance instance = null;        

		try {
			//creates manager instance
			RunInstancesRequest request = new RunInstancesRequest("ami-b66ed3de", 1, 1);
			request.setKeyName("test");
			request.setInstanceType(InstanceType.T1Micro.toString());
			ArrayList<String> commands = new ArrayList<String>();
			commands.add("#!/bin/bash");
			commands.add("aws configure set aws_access_key_id " + new ProfileCredentialsProvider().getCredentials().getAWSAccessKeyId());
			commands.add("aws configure set aws_secret_access_key " + new ProfileCredentialsProvider().getCredentials().getAWSSecretKey());
			commands.add("aws s3 cp s3://" + bucketName + "/manager.jar home/ec2-user/manager.jar");
			commands.add("yes | sudo yum install java-1.8.0");
			commands.add("yes | sudo yum remove java-1.7.0-openjdk");
			commands.add("sudo java -jar home/ec2-user/manager.jar " + workerCounter);

			StringBuilder builder = new StringBuilder();

			Iterator<String> commandsIterator = commands.iterator();

			while (commandsIterator.hasNext()) {
				builder.append(commandsIterator.next());
				if (!commandsIterator.hasNext()) {
					break;
				}
				builder.append("\n");
			}

			String userData = new String(Base64.encode(builder.toString().getBytes()));
			request.setUserData(userData);
			System.out.println("before running instance");
			instance = ec2.runInstances(request).getReservation().getInstances().get(0);
			System.out.println("after running instance");
			CreateTagsRequest request7 = new CreateTagsRequest();
			request7 = request7.withResources(instance.getInstanceId())
					.withTags(new Tag("Manager", ""));
			ec2.createTags(request7);
			System.out.println("Launch instance: " + instance);

		} catch (AmazonServiceException ase) {
			System.out.println("Cannot create instance : "+ ase);
		}

		return instance;
	}

	private static void createS3() {
		for (Bucket bucket : s3.listBuckets()) {
			if(bucket.getName().equals(bucketName)) {
				System.out.println("bucket.getName: (returns instead of creating bucket" + bucket.getName()); 
				return;
			}
		}

		try {
			System.out.println("Creating bucket " + bucketName + "\n");
			s3.createBucket(bucketName);
			System.out.println("S3 bucket has been created! Congratsulations");

		} catch (AmazonServiceException ase) {
			System.out.println("Caught an AmazonServiceException, which means your request made it "
					+ "to Amazon S3, but was rejected with an error response for some reason.");
		} catch (AmazonClientException ace) {
			System.out.println("Caught an AmazonClientException, which means the client encountered "
					+ "a serious internal problem while trying to communicate with S3, "
					+ "such as not being able to access the network.");
		}
	}
	private static String getQueue(String queueName) {
		for (String queueUrl : sqs.listQueues().getQueueUrls()) {
			if(queueName.equals(queueUrl.substring(queueUrl.lastIndexOf('/') + 1)))
				return queueUrl;
		}

		try {
			CreateQueueRequest createQueueRequest = new CreateQueueRequest(queueName);
			return sqs.createQueue(createQueueRequest).getQueueUrl();
		}

		catch (AmazonServiceException ase) {
			System.out.println("Caught an AmazonServiceException, which means your request made it " +
					"to Amazon SQS, but was rejected with an error response for some reason.");
		} 

		catch (AmazonClientException ace) {
			System.out.println("Error Message: " + ace.getMessage());
		}

		return null;
	}

	//TOFIX : One file is enough
	private static void uploadFiles(AmazonS3 s3, String[] args) {     
		System.out.println("Uploading jar files\n");
		String key = null;
		File file = null;

		file = new File(args[0]);
		key = file.getName();
		System.out.println("key: " + key + "\n");
		System.out.println("file: " + file + "\n");
		PutObjectRequest req = new PutObjectRequest(bucketName, key, file);
		//The application will send a message to a specified SQS queue, stating the location of the images list on S3
		s3.putObject(req);
		System.out.println("after object request");
	}
}